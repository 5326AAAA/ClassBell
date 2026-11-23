package cn.classbell.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class CameraXQrPreview(context: Context) : LiveQrPreview {
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }
    override val view: View get() = previewView

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val frameInFlight = AtomicBoolean()
    @Volatile private var active: Session? = null
    private var closed = false
    private val startupTimeout = Runnable { active?.let(::fail) }

    init {
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val session = active ?: return false
                val zoom = session.camera?.cameraInfo?.zoomState?.value ?: return false
                if (!session.ready || zoom.maxZoomRatio <= zoom.minZoomRatio) return false
                session.targetZoom = zoom.zoomRatio
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val session = active ?: return false
                val camera = session.camera ?: return false
                val zoom = camera.cameraInfo.zoomState.value ?: return false
                val target = (session.targetZoom * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                if (!session.ready || !target.isFinite() || target == session.targetZoom) return true
                session.targetZoom = target
                cleanup { camera.cameraControl.setZoomRatio(target) }
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                val session = active ?: return false
                val camera = session.camera ?: return false
                if (!session.ready) return false
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                if (!camera.cameraInfo.isFocusMeteringSupported(action)) return false
                cleanup { camera.cameraControl.startFocusAndMetering(action) }
                previewView.performClick()
                return true
            }
        })
        var multiTouch = false
        previewView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                multiTouch = false
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !multiTouch) {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                multiTouch = true
                MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }.let {
                    try { tap.onTouchEvent(it) } finally { it.recycle() }
                }
            }
            scale.onTouchEvent(event)
            if (!multiTouch) tap.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                view.parent?.requestDisallowInterceptTouchEvent(false)
            true
        }
    }

    @MainThread
    override fun start(onReady: (hasFlash: Boolean) -> Unit, onCodes: (List<String>) -> Unit, onError: () -> Unit) {
        if (closed || active != null) return
        val session = Session(onReady, onCodes, onError)
        active = session
        session.lifecycle.currentState = Lifecycle.State.STARTED
        main.postDelayed(startupTimeout, 10_000L)
        val future = try {
            ProcessCameraProvider.getInstance(previewView.context.applicationContext)
        } catch (_: Exception) {
            fail(session)
            return
        }
        future.addListener({
            if (active !== session) return@addListener
            val provider = try {
                future.get()
            } catch (_: Exception) {
                fail(session)
                return@addListener
            }
            bind(session, provider)
        }, mainExecutor)
    }

    private fun bind(session: Session, provider: ProcessCameraProvider) {
        val camera = try {
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> null
            }
            if (selector == null) {
                fail(session)
                return
            }
            session.provider = provider
            session.scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
            val surfaceProvider = previewView.surfaceProvider
            val preview = Preview.Builder().build().also { session.preview = it }
            preview.setSurfaceProvider { request ->
                if (active === session) {
                    session.surfaceRequested = true
                    surfaceProvider.onSurfaceRequested(request)
                } else {
                    request.willNotProvideSurface()
                }
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { session.analysis = it }
            analysis.setAnalyzer(frameExecutor) { analyze(session, it) }
            provider.bindToLifecycle(session, selector, preview, analysis)
        } catch (_: Exception) {
            fail(session)
            return
        }
        session.camera = camera
        camera.cameraInfo.cameraState.observe(session) { state ->
            if (state.error != null) fail(session)
        }
        if (active !== session) return
        previewView.previewStreamState.observe(session) { markReady(session) }
    }

    private fun markReady(session: Session) {
        if (active !== session || session.ready || !session.surfaceRequested || !session.hasFrame.get() ||
            previewView.previewStreamState.value != PreviewView.StreamState.STREAMING
        ) return
        session.ready = true
        main.removeCallbacks(startupTimeout)
        session.onReady(session.camera?.cameraInfo?.hasFlashUnit() == true)
    }

    private fun analyze(session: Session, image: ImageProxy) {
        if (active !== session) {
            image.close()
            return
        }
        // PreviewView 会保留上一个会话的流状态；同时确认当前会话已经收到相机帧。
        if (session.hasFrame.compareAndSet(false, true)) main.post { markReady(session) }
        val now = SystemClock.elapsedRealtime()
        if (!session.ready || now < session.nextFrameAt || !frameInFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        // Limit YUV-to-bitmap copies independently of the preview frame rate.
        session.nextFrameAt = now + 120L
        val rotation: Int
        val bitmap = try {
            rotation = image.imageInfo.rotationDegrees
            image.toBitmap()
        } catch (_: Exception) {
            frameInFlight.set(false)
            main.post { fail(session) }
            return
        } finally {
            image.close()
        }
        // 相机帧已释放；ML Kit 只使用独立位图，旧会话的识别完成后再回收。
        main.post { scan(session, bitmap, rotation) }
    }

    private fun scan(session: Session, bitmap: Bitmap, rotation: Int) {
        val scanner = session.scanner
        if (active !== session || scanner == null) {
            bitmap.recycle()
            frameInFlight.set(false)
            return
        }
        val task = try {
            scanner.process(InputImage.fromBitmap(bitmap, rotation))
        } catch (_: Exception) {
            bitmap.recycle()
            frameInFlight.set(false)
            fail(session)
            return
        }
        session.processing = true
        task.addOnCompleteListener(mainExecutor) { result ->
            bitmap.recycle()
            session.processing = false
            try {
                if (active !== session) {
                    closeScanner(session)
                } else if (!result.isSuccessful) {
                    fail(session)
                } else {
                    val codes = result.result.mapNotNull { it.rawValue }
                    if (codes.isNotEmpty()) session.onCodes(codes)
                }
            } finally {
                frameInFlight.set(false)
            }
        }
    }

    @MainThread
    override fun setTorch(enabled: Boolean, onFailure: () -> Unit) {
        val session = active ?: return onFailure()
        val camera = session.camera ?: return onFailure()
        if (!session.ready || !camera.cameraInfo.hasFlashUnit()) return onFailure()
        val request = ++session.torchRequest
        val future = try {
            camera.cameraControl.enableTorch(enabled)
        } catch (_: Exception) {
            onFailure()
            return
        }
        future.addListener({
            if (active !== session || request != session.torchRequest) return@addListener
            val succeeded = try {
                future.get()
                true
            } catch (_: Exception) {
                false
            }
            if (!succeeded) onFailure()
        }, mainExecutor)
    }

    @MainThread
    override fun stop() {
        val session = active ?: return
        active = null
        main.removeCallbacks(startupTimeout)
        val preview = session.preview
        val analysis = session.analysis
        cleanup { analysis?.clearAnalyzer() }
        cleanup { if (preview != null && analysis != null) session.provider?.unbind(preview, analysis) }
        cleanup { session.lifecycle.currentState = Lifecycle.State.DESTROYED }
        cleanup { preview?.setSurfaceProvider(null) }
        if (!session.processing) closeScanner(session)
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        try {
            stop()
        } finally {
            frameExecutor.shutdown()
        }
    }

    private fun fail(session: Session) {
        if (active !== session) return
        stop()
        session.onError()
    }

    private fun closeScanner(session: Session) {
        val scanner = session.scanner
        session.scanner = null
        cleanup { scanner?.close() }
    }

    private inline fun cleanup(action: () -> Unit) {
        try { action() } catch (_: Exception) { DiagnosticLog.event("QR", "live_camera_cleanup_failed", "WARN") }
    }

    private class Session(
        val onReady: (Boolean) -> Unit,
        val onCodes: (List<String>) -> Unit,
        val onError: () -> Unit
    ) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var camera: Camera? = null
        var scanner: BarcodeScanner? = null
        var surfaceRequested = false
        val hasFrame = AtomicBoolean()
        @Volatile var ready = false
        var nextFrameAt = 0L // frameExecutor only
        var processing = false
        var torchRequest = 0
        var targetZoom = 1f
    }
}
