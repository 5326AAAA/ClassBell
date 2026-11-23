package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only decoded material, never a session. Binding to the authenticated course is checked again at commit. */
class SignQr private constructor(val officialUrl:String,val activityId:String,internal val enc:String) {
    override fun toString()="SignQr(redacted)"
    fun requireFor(activity:SignActivity) { if(activityId!=activity.id)throw ApiProblem("二维码不属于当前签到，请重新扫描对应课程的二维码。") }
    companion object {
        fun parse(raw:String):SignQr? {
            if(raw.length !in 1..4096 || raw.any{it.isWhitespace() || it.code<32})return null
            val normalized=if(raw.startsWith("SIGNIN:"))"https://mobilelearn.chaoxing.com/widget/sign/e?"+raw.removePrefix("SIGNIN:").removePrefix("?") else raw
            val url=normalized.toHttpUrlOrNull() ?: return null
            if(url.scheme!="https" || url.host!="mobilelearn.chaoxing.com" || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment!=null || url.encodedPath!="/widget/sign/e")return null
            val allowed=setOf("id","aid","c","enc","DB_STRATEGY","STRATEGY_PARA")
            if(url.queryParameterNames.any{it !in allowed || url.queryParameterValues(it).size!=1})return null
            val id=url.queryParameter("id") ?: url.queryParameter("aid") ?: return null
            if(url.queryParameter("id")!=null && url.queryParameter("aid")!=null)return null
            val enc=url.queryParameter("enc").orEmpty()
            if(!Regex("[0-9]{1,30}").matches(id) || url.queryParameter("c")?.let{!Regex("[0-9]{1,30}").matches(it)}==true || !Regex("[a-fA-F0-9]{8,256}").matches(enc))return null
            if(url.queryParameter("DB_STRATEGY")?.let{it!="PRIMARY_KEY"}==true || url.queryParameter("STRATEGY_PARA")?.let{it!="id"}==true)return null
            return SignQr(url.toString(),id,enc)
        }
    }
}

object QrImage {
    private const val MAX_BYTES=16*1024*1024
    fun read(context:Context,uri:Uri):String {
        val bitmap=loadBitmap(context,uri)
        return try{decodeSelected(bitmap,null)}finally{bitmap.recycle()}
    }

    internal fun loadBitmap(context:Context,uri:Uri):Bitmap {
        if(uri.scheme!="content")throw ApiProblem("请从系统相册选择图片。")
        val bytes=context.contentResolver.openInputStream(uri)?.use{input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){checkCancelled();val size=input.read(buffer);if(size<0)break;if(output.size()+size>MAX_BYTES)throw ApiProblem("图片超过 16 MB，请裁剪二维码后重试。");output.write(buffer,0,size)}
            output.toByteArray()
        } ?: throw ApiProblem("未能读取所选图片，请重新选择。")
        checkCancelled()
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>30_000 || bounds.outHeight>30_000)throw ApiProblem("图片格式或尺寸不受支持。")
        val options=BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1;while(maxOf(bounds.outWidth/inSampleSize,bounds.outHeight/inSampleSize)>2048)inSampleSize*=2}
        checkCancelled()
        val orientation=try {
            ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }
        } catch (_:IOException) { ExifInterface.ORIENTATION_NORMAL }
        catch (_:IllegalArgumentException) { ExifInterface.ORIENTATION_NORMAL }
        var bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: throw ApiProblem("未能读取图片内容。")
        try {
            requireUsable(bitmap)
            checkCancelled()
            val upright=orientBitmap(bitmap,orientation)
            if(upright!==bitmap){bitmap.recycle();bitmap=upright}
            checkCancelled()
            return bitmap
        } catch (error:Exception) {
            bitmap.recycle()
            throw error
        }
    }

    /** Source ownership stays with the caller. Crop pixels are mapped after normalization. */
    internal fun orientBitmap(bitmap:Bitmap,orientation:Int):Bitmap {
        val matrix=Matrix().apply {
            when(orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(270f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270->setRotate(270f)
            }
        }
        return if(matrix.isIdentity)bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,false)
    }

    internal fun decodeSelected(bitmap:Bitmap,crop:Rect?):String {
        requireUsable(bitmap)
        checkCancelled()
        if(crop==null)return decode(bitmap,MlKitQrDecoder::decode)
        val bounds=Rect(crop)
        if(!bounds.intersect(0,0,bitmap.width,bitmap.height) ||
            bounds.width()<minOf(MIN_QR_CROP_PIXELS,bitmap.width) || bounds.height()<minOf(MIN_QR_CROP_PIXELS,bitmap.height)) {
            throw ApiProblem("裁剪范围太小，请保留完整二维码及四周留白。")
        }
        val selected=Bitmap.createBitmap(bitmap,bounds.left,bounds.top,bounds.width(),bounds.height())
        return try{decode(selected,MlKitQrDecoder::decode)}finally{if(selected!==bitmap)selected.recycle()}
    }

    private fun requireUsable(bitmap:Bitmap) {
        if(bitmap.isRecycled || bitmap.width.toLong()*bitmap.height>4_194_304)throw ApiProblem("图片尺寸过大或不可用，请裁剪二维码后重试。")
    }

    internal fun decode(bitmap:Bitmap,fallback:((Bitmap)->String?)?=null):String {
        requireUsable(bitmap)
        checkCancelled()
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
        val reader=MultiFormatReader()
        val hints=mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        try {
            // QR detection already handles rotation. Try both polarities and thresholding methods
            // before loading the heavier bundled ML model for difficult gallery images.
            for(candidate in listOf(source,source.invert())) {
                for(global in listOf(false,true)) {
                    checkCancelled()
                    try {
                        val binary=BinaryBitmap(if(global)GlobalHistogramBinarizer(candidate) else HybridBinarizer(candidate))
                        return reader.decode(binary,hints).text
                    } catch(_:ReaderException) {
                        // An ordinary miss is not an application error and never includes QR data.
                    } finally { reader.reset() }
                }
            }
        } finally { reader.reset() }
        if(fallback!=null) {
            checkCancelled()
            try {
                fallback(bitmap)?.takeIf{it.isNotEmpty()}?.let{return it}
            } catch(e:InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Image decoding cancelled")
            } catch(e:CancellationException) {
                throw e
            } catch(_:TimeoutException) {
                throw ApiProblem("本机识别超时，请裁剪到单个二维码或改用摄像头扫码。")
            } catch(_:IllegalArgumentException) {
                throw ApiProblem("图片中包含多个二维码或内容不受支持，请裁剪到单个二维码。")
            } catch(_:Exception) {
                // ML Kit failures can carry image metadata; record only the engine outcome.
                DiagnosticLog.event("QR","image_fallback_unavailable")
                throw ApiProblem("备用识别引擎暂不可用，请裁剪二维码或改用摄像头扫码。")
            }
        }
        throw ApiProblem("没有识别到二维码，请选清晰、完整的图片。")
    }
    private fun checkCancelled() {
        if(Thread.currentThread().isInterrupted)throw CancellationException("Image decoding cancelled")
    }
}

private const val MIN_QR_CROP_PIXELS = 24

internal fun fitQrPreview(bitmapWidth: Int, bitmapHeight: Int, frame: RectF): RectF {
    val scale = minOf(frame.width() / bitmapWidth, frame.height() / bitmapHeight)
    val halfWidth = bitmapWidth * scale / 2f
    val halfHeight = bitmapHeight * scale / 2f
    return RectF(frame.centerX() - halfWidth, frame.centerY() - halfHeight,
        frame.centerX() + halfWidth, frame.centerY() + halfHeight)
}

internal fun mapQrCrop(selection: RectF, previewBounds: RectF, bitmapWidth: Int, bitmapHeight: Int): Rect {
    fun pixel(value: Float, offset: Float, span: Float, size: Int): Double =
        ((value.toDouble() - offset) / span).coerceIn(0.0, 1.0) * size
    return Rect(
        floor(pixel(selection.left, previewBounds.left, previewBounds.width(), bitmapWidth)).toInt(),
        floor(pixel(selection.top, previewBounds.top, previewBounds.height(), bitmapHeight)).toInt(),
        ceil(pixel(selection.right, previewBounds.left, previewBounds.width(), bitmapWidth)).toInt(),
        ceil(pixel(selection.bottom, previewBounds.top, previewBounds.height(), bitmapHeight)).toInt()
    )
}

internal fun showQrCropDialog(
    activity: Activity,
    preview: Bitmap,
    onDecode: (Rect?) -> Unit,
    onDismiss: () -> Unit
): AlertDialog {
    fun dp(size: Int) = ModernUi.dp(activity, size)
    val editor = QrCropView(activity, preview)
    val previewHeight = (activity.resources.configuration.screenHeightDp * 0.46f).toInt().coerceIn(112, 330)
    val content = StudyUi.column(activity).apply {
        setPadding(dp(20), dp(4), dp(20), dp(8))
        addView(StudyUi.text(activity, "拖动裁剪框或边角，只保留一个完整二维码和四周留白。", 14, color = ModernUi.ink))
        addView(editor, LinearLayout.LayoutParams(-1, dp(previewHeight)).apply { topMargin = dp(8) })
        addView(StudyUi.text(activity, "仅在本机识别 · 图片不会上传", 12, color = ModernUi.strongPrimary).apply {
            setPaddingRelative(dp(12), dp(5), dp(12), dp(5))
            background = ModernUi.shape(context, ModernUi.soft, 20)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }
    val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
    }
    val dialog = ModernUi.dialog(activity)
        .setTitle("裁剪二维码")
        .setView(scroll)
        .setPositiveButton("裁剪并识别", null)
        .setNeutralButton("原图识别", null)
        .setNegativeButton("取消", null)
        .create()
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnDismissListener { editor.releasePreview(); onDismiss() }
    dialog.show()
    fun decode(crop: Rect?) {
        try { onDecode(crop) } finally { dialog.dismiss() }
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { editor.selectedPixels()?.let { decode(it) } }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { decode(null) }
    return dialog
}

private class QrCropView(context: Context, private var preview: Bitmap?) : View(context) {
    private val bitmapWidth = checkNotNull(preview).width
    private val bitmapHeight = checkNotNull(preview).height
    private val bounds = RectF()
    private val selection = RectF()
    private val drawnSelection = RectF()
    private val corners = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inset = dp(12)
    private val handleRadius = dp(24)
    private val minimumSize = dp(32)
    private var drag = NONE
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f

    init {
        background = ModernUi.shape(context, 0xFF132A25.toInt(), 16)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "二维码裁剪框，可移动或缩放"
        ViewCompat.addAccessibilityAction(this, "向左移动") { _, _ -> move(-STEP, 0f) }
        ViewCompat.addAccessibilityAction(this, "向右移动") { _, _ -> move(STEP, 0f) }
        ViewCompat.addAccessibilityAction(this, "向上移动") { _, _ -> move(0f, -STEP) }
        ViewCompat.addAccessibilityAction(this, "向下移动") { _, _ -> move(0f, STEP) }
        ViewCompat.addAccessibilityAction(this, "扩大裁剪框") { _, _ -> resize(STEP) }
        ViewCompat.addAccessibilityAction(this, "缩小裁剪框") { _, _ -> resize(-STEP) }
    }

    fun releasePreview() { preview = null }

    fun selectedPixels(): Rect? = if (bounds.isEmpty || selection.isEmpty) null else
        mapQrCrop(drawnBounds(), bounds, bitmapWidth, bitmapHeight)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 2 * inset || h <= 2 * inset) {
            bounds.setEmpty()
            return
        }
        bounds.set(fitQrPreview(bitmapWidth, bitmapHeight, RectF(inset, inset, w - inset, h - inset)))
        if (selection.isEmpty) {
            val x = minOf(0.06f, (1f - minWidth()) / 2f)
            val y = minOf(0.06f, (1f - minHeight()) / 2f)
            selection.set(x, y, 1f - x, 1f - y)
        }
    }

    private fun drawnBounds(): RectF {
        drawnSelection.set(
            bounds.left + selection.left * bounds.width(), bounds.top + selection.top * bounds.height(),
            bounds.left + selection.right * bounds.width(), bounds.top + selection.bottom * bounds.height()
        )
        return drawnSelection
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = preview ?: return
        if (image.isRecycled || bounds.isEmpty) return
        val crop = drawnBounds()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawBitmap(image, null, bounds, paint)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.clipOutRect(crop)
        canvas.drawColor(0x99091916.toInt())
        canvas.restore()
        if (drag != NONE) {
            paint.color = 0xAAFFFFFF.toInt()
            paint.strokeWidth = dp(1)
            for (i in 1..2) {
                val x = crop.left + i * crop.width() / 3
                val y = crop.top + i * crop.height() / 3
                canvas.drawLine(x, crop.top, x, crop.bottom, paint)
                canvas.drawLine(crop.left, y, crop.right, y, paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.color = 0xCC09241A.toInt()
        paint.strokeWidth = dp(3)
        canvas.drawRect(crop, paint)
        val accent = if (isFocused || drag != NONE) 0xFF8BE7BC.toInt() else Color.WHITE
        paint.color = accent
        paint.strokeWidth = dp(1)
        canvas.drawRect(crop, paint)
        val arm = minOf(dp(16), crop.width() / 3f, crop.height() / 3f)
        corners.rewind()
        for (side in 0..3) {
            val left = side % 2 == 0
            val top = side < 2
            val x = if (left) crop.left else crop.right
            val y = if (top) crop.top else crop.bottom
            corners.moveTo(x, y + if (top) arm else -arm)
            corners.lineTo(x, y)
            corners.lineTo(x + if (left) arm else -arm, y)
        }
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xDE09241A.toInt()
        paint.strokeWidth = dp(7)
        canvas.drawPath(corners, paint)
        paint.color = accent
        paint.strokeWidth = dp(4)
        canvas.drawPath(corners, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bounds.isEmpty) return false
                val crop = drawnBounds()
                val x = event.x
                val y = event.y
                val hitX = minOf(handleRadius, crop.width() / 3f)
                val hitY = minOf(handleRadius, crop.height() / 3f)
                if (x < crop.left - hitX || x > crop.right + hitX || y < crop.top - hitY || y > crop.bottom + hitY) return false
                drag = MOVE
                if (abs(x - crop.left) <= hitX) drag = drag or LEFT
                if (abs(x - crop.right) <= hitX) drag = drag or RIGHT
                if (abs(y - crop.top) <= hitY) drag = drag or TOP
                if (abs(y - crop.bottom) <= hitY) drag = drag or BOTTOM
                pointer = event.getPointerId(0)
                lastX = x
                lastY = y
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (drag == NONE || index < 0 || bounds.isEmpty) return true
                val x = event.getX(index)
                val y = event.getY(index)
                dragBy((x - lastX) / bounds.width(), (y - lastY) / bounds.height())
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) endDrag()
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> endDrag()
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-STEP, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(STEP, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> move(0f, -STEP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0f, STEP)
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> resize(STEP)
            KeyEvent.KEYCODE_MINUS -> resize(-STEP)
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun endDrag() {
        drag = NONE
        pointer = MotionEvent.INVALID_POINTER_ID
        parent.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun dragBy(dx: Float, dy: Float) {
        if (drag == MOVE) {
            move(dx, dy)
            return
        }
        val width = minOf(minWidth(), selection.width())
        val height = minOf(minHeight(), selection.height())
        if (drag and LEFT != 0) selection.left = (selection.left + dx).coerceIn(0f, selection.right - width)
        if (drag and RIGHT != 0) selection.right = (selection.right + dx).coerceIn(selection.left + width, 1f)
        if (drag and TOP != 0) selection.top = (selection.top + dy).coerceIn(0f, selection.bottom - height)
        if (drag and BOTTOM != 0) selection.bottom = (selection.bottom + dy).coerceIn(selection.top + height, 1f)
        invalidate()
    }

    private fun move(dx: Float, dy: Float): Boolean {
        if (selection.isEmpty || bounds.isEmpty) return false
        val movedX = dx.coerceIn(-selection.left, 1f - selection.right)
        val movedY = dy.coerceIn(-selection.top, 1f - selection.bottom)
        if (movedX == 0f && movedY == 0f) return false
        selection.set((selection.left + movedX).coerceIn(0f, 1f), (selection.top + movedY).coerceIn(0f, 1f),
            (selection.right + movedX).coerceIn(0f, 1f), (selection.bottom + movedY).coerceIn(0f, 1f))
        invalidate()
        return true
    }

    private fun resize(amount: Float): Boolean {
        if (selection.isEmpty || bounds.isEmpty) return false
        val x = amount.coerceAtLeast(-((selection.width() - minWidth()) / 2f).coerceAtLeast(0f))
        val y = amount.coerceAtLeast(-((selection.height() - minHeight()) / 2f).coerceAtLeast(0f))
        val left = (selection.left - x).coerceAtLeast(0f)
        val top = (selection.top - y).coerceAtLeast(0f)
        val right = (selection.right + x).coerceAtMost(1f)
        val bottom = (selection.bottom + y).coerceAtMost(1f)
        if (selection.left == left && selection.top == top && selection.right == right && selection.bottom == bottom) return false
        selection.set(left, top, right, bottom)
        invalidate()
        return true
    }

    private fun minWidth() = maxOf((minimumSize / bounds.width()).coerceAtMost(0.55f),
        (MIN_QR_CROP_PIXELS.toFloat() / bitmapWidth).coerceAtMost(1f))
    private fun minHeight() = maxOf((minimumSize / bounds.height()).coerceAtMost(0.55f),
        (MIN_QR_CROP_PIXELS.toFloat() / bitmapHeight).coerceAtMost(1f))
    private fun dp(value: Int) = ModernUi.dp(context, value).toFloat()

    private companion object {
        const val NONE = -1
        const val MOVE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val TOP = 4
        const val BOTTOM = 8
        const val STEP = 0.06f
    }
}
