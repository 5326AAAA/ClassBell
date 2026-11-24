package cn.classbell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*

/** Preview-only camera engine. Its callbacks and controls run on the main thread. */
internal interface LiveQrPreview {
    val view: View
    fun start(onReady: (hasFlash: Boolean) -> Unit, onCodes: (List<String>) -> Unit, onError: () -> Unit)
    fun setTorch(enabled: Boolean, onFailure: () -> Unit)
    fun stop()
    fun close()
}

/** Decode locally and return material; this screen never submits or transfers a login session. */
class QrScanActivity:StudyActivity() {
    override val requestedBoundAccountId:String? get()=intent.getStringExtra("boundAccountId")
    private val expectedId get()=intent.getStringExtra("expectedId")
    private val previewOnly get()=intent.getBooleanExtra(PREVIEW_ONLY,false)
    private var signSource:SignSource?=null
    internal var lookupSourceFactory:((()->Unit)->SignSource)?=null
    private lateinit var camera:BarcodeView
    private lateinit var liveContainer:FrameLayout
    private lateinit var viewfinder:FrameLayout
    private lateinit var guide:QrViewfinderGuide
    private lateinit var stopButton:Button
    internal var livePreviewFactory:(android.content.Context)->LiveQrPreview = ::CameraXQrPreview
    private var livePreview:LiveQrPreview?=null
    private enum class Engine { CAMERAX, ZXING }
    internal enum class PreviewInput { CAMERA, GALLERY }
    private var engine=Engine.CAMERAX
    private var liveHasFlash=false
    private lateinit var result:LinearLayout
    private lateinit var torchButton:Button
    private lateinit var settingsButton:Button
    private var wanted=false
    private var resumed=false
    private var closed=false
    private var cameraActive=false
    private var previewReady=false
    private var cameraGeneration=0
    private var permissionPending=false
    private var torchOn=false
    private var selected:SignQr?=null
    private var lastScanHint:String?=null
    private var initialInput:PreviewInput?=null
    private var cropDialog:android.app.AlertDialog?=null
    private val torchAvailable get()=packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    private fun usable()=!closed && !isDestroyed && !isFinishing && valid()
    private fun hasCameraPermission()=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!valid()){finish();return}
        titleView.text=if(previewOnly)"二维码识别测试" else "扫码签到"
        state.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        val intro=StudyUi.card(this,body)
        intro.addView(StudyUi.text(this,if(previewOnly)"本地识别测试" else "对准当前签到二维码",21,true))
        intro.addView(StudyUi.text(this,when {
            previewOnly->"网页只接收识别状态，不会提交签到。"
            expectedId!=null->"账号与签到要求已在扫码前核对，识别当前码后立即提交。"
            else->"先识别本次签到，账号准备完成后自动继续扫码并提交，无需手动确认。"
        },14))
        intro.addView(StudyUi.text(this,"摄像头和相册都可使用 · 图片不上传",12,true,ModernUi.strongPrimary))
        viewfinder=FrameLayout(this).apply {
            visibility=View.GONE;isSaveFromParentEnabled=false
            background=ModernUi.shape(this@QrScanActivity,Color.rgb(22,43,36),18)
            clipToOutline=true
        }
        val height=(resources.configuration.screenHeightDp*.39f).toInt().coerceIn(200,340)
        body.addView(viewfinder,LinearLayout.LayoutParams(-1,ModernUi.dp(this,height)))
        liveContainer=FrameLayout(this).apply{visibility=View.GONE;isSaveFromParentEnabled=false}
        viewfinder.addView(liveContainer,FrameLayout.LayoutParams(-1,-1))
        camera=BarcodeView(this).apply {
            visibility=View.GONE;isSaveEnabled=false;isSaveFromParentEnabled=false;decoderFactory=DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            addStateListener(object:CameraPreview.StateListener{
                override fun previewSized(){}
                override fun previewStarted(){if(engine==Engine.ZXING && usable() && resumed && cameraActive){previewReady=true;updateTorch()}}
                override fun previewStopped(){if(engine==Engine.ZXING && usable()){previewReady=false;updateTorch()}}
                override fun cameraClosed(){}
                override fun cameraError(error:Exception){
                    DiagnosticLog.error("Camera",error)
                    if(engine==Engine.ZXING && usable() && resumed && cameraActive){stopCamera();state.text="相机暂时不可用，可从相册选择二维码。"}
                }
            })
        }
        viewfinder.addView(camera,FrameLayout.LayoutParams(-1,-1))
        guide=QrViewfinderGuide(this)
        viewfinder.addView(guide,FrameLayout.LayoutParams(-1,-1))
        stopButton=StudyUi.button(this,"停止扫码"){
            if(usable() && resumed && cameraActive){stopCamera();state.text="已停止扫码，摄像头已关闭。"}
        }.apply{visibility=View.GONE}
        body.addView(stopButton)
        body.addView(StudyUi.button(this,"摄像头扫码",true){startCamera(Engine.CAMERAX)})
        body.addView(StudyUi.button(this,"兼容扫码（ZXing）"){startCamera(Engine.ZXING)})
        torchButton=StudyUi.button(this,"打开手电筒"){toggleTorch()};body.addView(torchButton);updateTorch()
        settingsButton=StudyUi.button(this,"打开应用设置"){
            if(usable() && resumed && wanted && !hasCameraPermission()){
                try{startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null)))}
                catch(error:Exception){DiagnosticLog.error("CameraSettings",error);state.text="无法打开应用设置，请在系统设置中为本应用开启相机权限。"}
            }
        }.apply{visibility=View.GONE};body.addView(settingsButton)
        body.addView(StudyUi.button(this,"从相册识别二维码"){pickImage()})
        result=StudyUi.column(this).apply{isSaveFromParentEnabled=false};body.addView(result)
        if(!previewOnly)body.addView(StudyUi.text(this,"刷新周期：尚未获取\n静态截图不含刷新秒数时，无法据此判断下一次换码时间。已过期的码请重新扫描。",12))
        if(savedInstanceState!=null)state.text="页面已恢复，为保护二维码信息，请重新扫描或从相册识别。"
        else if(previewOnly)initialInput=PreviewInput.entries.firstOrNull{it.name==intent.getStringExtra(PREVIEW_INPUT)}
    }
    private fun startCamera(selectedEngine:Engine) {
        DiagnosticLog.event("QR", "camera_requested session_valid=${valid()} permission=${hasCameraPermission()}")
        if(!usable() || !resumed || permissionPending)return
        signSource?.cancel();signSource=null;cancelWork();stopCamera();selected=null;result.removeAllViews();engine=selectedEngine;wanted=true
        settingsButton.visibility=View.GONE
        if(!hasCameraPermission()){
            state.text="摄像头扫码需要相机权限；只用于识别二维码，也可不授权并从相册识别。"
            permissionPending=true;requestPermissions(arrayOf(Manifest.permission.CAMERA),CAMERA);return
        }
        resumeCamera()
    }
    private fun resumeCamera() {
        if(!usable() || !resumed || !wanted || cameraActive || !hasCameraPermission())return
        val token=++cameraGeneration
        cameraActive=true;previewReady=false;liveHasFlash=false;lastScanHint=null
        viewfinder.visibility=View.VISIBLE;stopButton.visibility=View.VISIBLE;guide.highlight=false
        settingsButton.visibility=View.GONE;state.text="正在启动相机…";updateTorch()
        if(engine==Engine.CAMERAX){
            try {
                val preview=livePreview ?: livePreviewFactory(this).also {
                    livePreview=it
                    liveContainer.addView(it.view,FrameLayout.LayoutParams(-1,-1))
                }
                liveContainer.visibility=View.VISIBLE
                preview.start(
                    onReady={hasFlash->if(cameraCurrent(token)){
                        previewReady=true;liveHasFlash=hasFlash
                        state.text="把完整二维码放进取景画面。可点击对焦、双指缩放。";updateTorch()
                    }},
                    onCodes={raw->onCameraCodes(token,raw)},
                    onError={liveCameraFailed(token)}
                )
            } catch(error:Exception) {
                DiagnosticLog.error("LiveCameraStart",error)
                liveCameraFailed(token)
            }
        } else {
            try {
                camera.visibility=View.VISIBLE;state.text="把完整二维码放进取景画面。"
                camera.decodeContinuous { value -> onCameraCodes(token,listOf(value.text)) }
                camera.resume()
            } catch(error:Exception) {
                DiagnosticLog.error("CameraStart",error);stopCamera()
                state.text="兼容相机暂时不可用，可从相册选择二维码。"
            }
        }
    }
    private fun cameraCurrent(token:Int)=token==cameraGeneration && cameraActive && wanted && resumed && usable()
    private fun onCameraCodes(token:Int,raw:List<String>) {
        if(!cameraCurrent(token) || raw.isEmpty())return
        val supported=raw.distinct().mapNotNull{SignQr.parse(it)}
        val expected=expectedId
        val matching=supported.filter{(expected==null || it.activityId==expected) && it.enc!=intent.getStringExtra(REJECTED_ENC)}
        val material=matching.singleOrNull()
        if(material!=null){showMaterial(material);return}
        val hint=when {
            supported.any{it.enc==intent.getStringExtra(REJECTED_ENC)}->"这张码已失效，正在等待老师更新二维码。"
            matching.size>1->"画面中有多个签到二维码，请只对准一个。"
            supported.isNotEmpty()->"二维码与所选签到不一致，请继续扫描当前活动的二维码。"
            else->"这不是支持的签到二维码，请继续扫描。"
        }
        if(lastScanHint!=hint || state.text!=hint){lastScanHint=hint;state.text=hint;guide.highlight=true}
    }
    private fun liveCameraFailed(token:Int) {
        if(!cameraCurrent(token))return
        stopCamera();state.text="实时相机暂不可用，请尝试兼容扫码（ZXing）或从相册识别。"
    }
    private fun updateTorch() {
        if(!::torchButton.isInitialized)return
        val hasFlash=if(engine==Engine.CAMERAX && previewReady)liveHasFlash else torchAvailable
        torchButton.visibility=if(hasFlash)View.VISIBLE else View.GONE
        torchButton.isEnabled=hasFlash && cameraActive && previewReady && resumed && usable()
        torchButton.text=if(torchOn)"关闭手电筒" else "打开手电筒"
    }
    private fun toggleTorch() {
        if(!usable() || !resumed || !cameraActive || !previewReady || !torchButton.isEnabled)return
        val next=!torchOn
        try {
            torchOn=next;updateTorch()
            if(engine==Engine.CAMERAX){
                val token=cameraGeneration
                checkNotNull(livePreview).setTorch(next){if(cameraCurrent(token) && torchOn==next){
                    torchOn=!next;updateTorch();state.text="手电筒暂时不可用。"
                }}
            } else camera.setTorch(next)
        } catch(error:Exception){
            DiagnosticLog.error("CameraTorch",error);torchOn=!next;updateTorch();state.text="手电筒暂时不可用。"
        }
    }
    private fun suspendCamera() {
        val wasActive=cameraActive
        cameraGeneration++;cameraActive=false;previewReady=false;torchOn=false;liveHasFlash=false
        if(::viewfinder.isInitialized)viewfinder.visibility=View.GONE
        if(::stopButton.isInitialized)stopButton.visibility=View.GONE
        if(engine==Engine.CAMERAX){
            try{livePreview?.stop()}catch(error:Exception){DiagnosticLog.error("LiveCameraStop",error)}
            if(::liveContainer.isInitialized)liveContainer.visibility=View.GONE
        }
        if(engine==Engine.ZXING && wasActive && ::camera.isInitialized){
            try{camera.stopDecoding();camera.setTorch(false)}
            catch(error:Exception){DiagnosticLog.error("CameraStop",error)}
            finally{
                try{camera.pause()}catch(error:Exception){DiagnosticLog.error("CameraPause",error)}
                camera.visibility=View.GONE
            }
        }
        updateTorch()
    }
    private fun stopCamera(){wanted=false;permissionPending=false;suspendCamera()}
    private fun pickImage() {
        DiagnosticLog.event("QR", "image_picker_requested session_valid=${valid()}")
        if(!usable() || !resumed)return
        signSource?.cancel();signSource=null;cancelWork();stopCamera();settingsButton.visibility=View.GONE;selected=null;result.removeAllViews()
        @Suppress("DEPRECATION")
        try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),IMAGE)}
        catch (diagnosticError: Exception) { DiagnosticLog.error("QrScanActivity", diagnosticError);state.text="系统图片选择器未能打开，请尝试摄像头扫码。"}
    }
    internal fun accept(raw:String) {
        if(!usable())return
        val material=SignQr.parse(raw)
        val expected=expectedId
        if(material==null || (expected!=null && material.activityId!=expected)){
            stopCamera();settingsButton.visibility=View.GONE;selected=null;result.removeAllViews()
            state.text=if(material==null)"未识别为支持的签到二维码，请重新选图或扫码。" else "二维码与所选签到不一致，请扫描对应课程的当前二维码。"
            return
        }
        showMaterial(material)
    }
    private fun showMaterial(material:SignQr) {
        if(material.enc==intent.getStringExtra(REJECTED_ENC)){state.text="这张码已失效，请识别更新后的二维码。";return}
        stopCamera();settingsButton.visibility=View.GONE;result.removeAllViews()
        DiagnosticLog.event("QR", "decoded supported=true")
        if(previewOnly){selected=null;setResult(RESULT_OK);finish();return}
        if(expectedId!=null){setResult(RESULT_OK,Intent().putExtra(MATERIAL,material.officialUrl).putExtra(CAPTURED_AT,material.capturedAt));finish();return}
        selected=null
        resolve(material)
    }
    private fun resolve(material:SignQr) {
        signSource?.cancel()
        val source=lookupSourceFactory?.invoke(::guard) ?: SigningApi(sessionCookies,::guard)
        signSource=source
        val courses=sessionCourses.toList()
        work("正在核对二维码所属课程…",{
            try { QrCourseLookup.find(material.activityId,courses,::guard,source::activities){DiagnosticLog.error("QrResolve",it)} }
            finally { source.cancel() }
        }){item->
            if(usable()){startActivity(SignActionActivity.intent(this,item,boundAccountId=requestedBoundAccountId).putExtra("autoScan",true));finish()}
        }
    }

    @Deprecated("Platform document picker") override fun onActivityResult(code:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(code,resultCode,data)
        if(code==IMAGE)DiagnosticLog.event("QR", "image_picker_result ok=${resultCode==RESULT_OK} selected=${data?.data!=null} session_valid=${valid()}")
        if(code!=IMAGE || !usable())return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null){state.text="未选择图片。可以重新选图或使用摄像头扫码。";return}
        work("正在读取图片预览…",{QrImage.loadBitmap(this,uri)}){bitmap->
            if(!usable())return@work
            var decoding=false
            state.text="选取二维码区域，或直接识别原图。"
            cropDialog=showQrCropDialog(this,bitmap,onDecode={crop->
                if(usable() && !working){
                    decoding=true
                    work("正在本机识别二维码…",{QrImage.decodeSelected(bitmap,crop)}){accept(it)}
                }
            },onDismiss={
                cropDialog=null
                if(!decoding && usable())state.text="已取消图片识别。"
            })
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        DiagnosticLog.event("Permissions", "request=$code granted=${results.count{it==PackageManager.PERMISSION_GRANTED}} total=${results.size}")
        if(code!=CAMERA || !permissionPending)return
        permissionPending=false
        if(!usable() || !wanted)return
        if(hasCameraPermission()){resumeCamera();return}
        suspendCamera()
        val permanentlyDenied=results.isNotEmpty() && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        settingsButton.visibility=if(permanentlyDenied)View.VISIBLE else View.GONE
        state.text=if(permanentlyDenied)"相机权限未开启，系统不再弹出授权。可点击“打开应用设置”手动开启，或从相册识别。" else "未获得相机权限，不会启动摄像头。可再次点击“摄像头扫码”授权，或从相册识别。"
    }
    override fun onResume(){
        super.onResume();resumed=true
        if(!usable()){stopCamera();return}
        val input=initialInput.also{initialInput=null} ?: if(intent.getBooleanExtra("autoStartCamera",false)){intent.removeExtra("autoStartCamera");PreviewInput.CAMERA}else null
        when {
            input==PreviewInput.CAMERA->startCamera(Engine.CAMERAX)
            input==PreviewInput.GALLERY->pickImage()
            wanted && ::camera.isInitialized && hasCameraPermission()->resumeCamera()
            else->updateTorch()
        }
    }
    override fun onPause(){resumed=false;suspendCamera();super.onPause()}
    override fun onDestroy(){
        closed=true;resumed=false;selected=null;cropDialog?.dismiss();signSource?.cancel();stopCamera()
        try{livePreview?.close()}catch(error:Exception){DiagnosticLog.error("LiveCameraClose",error)}
        finally{livePreview=null;super.onDestroy()}
    }
    companion object {
        const val IMAGE=5401;const val CAMERA=5402;const val MATERIAL="signQrMaterial"
        const val CAPTURED_AT="qrCapturedElapsed"
        const val REJECTED_ENC="rejectedQrEnc"
        private const val PREVIEW_ONLY="scanPreviewOnly"
        private const val PREVIEW_INPUT="scanPreviewInput"
        internal fun previewIntent(activity:android.app.Activity,input:PreviewInput?=null)=Intent(activity,QrScanActivity::class.java)
            .putExtra(PREVIEW_ONLY,true).putExtra(PREVIEW_INPUT,input?.name)
        fun materialIntent(activity:android.app.Activity,activeId:String,boundAccountId:String?=null)=Intent(activity,QrScanActivity::class.java)
            .putExtra("expectedId",activeId).putExtra("boundAccountId",boundAccountId)
    }
}

/** Visual guide only: never intercept taps or restrict decoding outside the corners. */
private class QrViewfinderGuide(context:android.content.Context):View(context) {
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    var highlight=false
        set(value){if(field!=value){field=value;invalidate()}}
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val edge=minOf(width,height)*.62f
        if(edge<=0f)return
        val left=(width-edge)/2f;val top=(height-edge)/2f
        val right=left+edge;val bottom=top+edge;val corner=edge*.13f
        fun drawCorners() {
            canvas.drawLine(left,top,left+corner,top,line);canvas.drawLine(left,top,left,top+corner,line)
            canvas.drawLine(right,top,right-corner,top,line);canvas.drawLine(right,top,right,top+corner,line)
            canvas.drawLine(left,bottom,left+corner,bottom,line);canvas.drawLine(left,bottom,left,bottom-corner,line)
            canvas.drawLine(right,bottom,right-corner,bottom,line);canvas.drawLine(right,bottom,right,bottom-corner,line)
        }
        line.strokeWidth=ModernUi.dp(context,5).toFloat();line.color=0xC8182B23.toInt();drawCorners()
        line.strokeWidth=ModernUi.dp(context,3).toFloat();line.color=if(highlight)0xFFFFD581.toInt() else Color.WHITE;drawCorners()
    }
}
