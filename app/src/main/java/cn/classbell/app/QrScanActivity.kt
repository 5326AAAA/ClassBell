package cn.classbell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*

/** Decode locally and return material; this screen never submits or transfers a login session. */
class QrScanActivity:StudyActivity() {
    override val requestedBoundAccountId:String? get()=intent.getStringExtra("boundAccountId")
    private val expectedId get()=intent.getStringExtra("expectedId")
    private var signSource:SignSource?=null
    private lateinit var camera:BarcodeView
    private lateinit var result:LinearLayout
    private var wanted=false
    private var selected:SignQr?=null
    private var permissionDialog:android.app.AlertDialog?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!valid()){finish();return}
        titleView.text="扫码签到"
        body.addView(StudyUi.text(this,"对准老师当前展示的二维码",22,true))
        body.addView(StudyUi.text(this,"摄像头和相册均可识别。识别后回到应用内核对账号并提交；识别本身不会执行签到。",14))
        camera=BarcodeView(this).apply {
            visibility=View.GONE;decoderFactory=DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            addStateListener(object:CameraPreview.StateListener{
                override fun previewSized(){};override fun previewStarted(){};override fun previewStopped(){};override fun cameraClosed(){}
                override fun cameraError(error:Exception){DiagnosticLog.error("Camera",error);if(!isDestroyed){stopCamera();state.text="相机暂时不可用，可从相册选择二维码。"}}
            })
        }
        body.addView(camera,LinearLayout.LayoutParams(-1,ModernUi.dp(this,280)))
        body.addView(StudyUi.button(this,"摄像头扫码",true){startCamera()})
        body.addView(StudyUi.button(this,"从相册识别二维码"){pickImage()})
        result=StudyUi.column(this);body.addView(result)
        body.addView(StudyUi.text(this,"刷新周期：尚未获取\n静态截图不含刷新秒数时，无法据此判断下一次换码时间。已过期的码请重新扫描。",12))
    }
    private fun startCamera() {
        DiagnosticLog.event("QR", "camera_requested session_valid=${valid()} permission=${checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}")
        if(!valid())return
        signSource?.cancel();signSource=null;cancelWork();selected=null;result.removeAllViews();wanted=true
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.CAMERA),CAMERA);return}
        camera.visibility=View.VISIBLE;state.text="把完整二维码放进取景画面。"
        camera.decodeSingle(object:BarcodeCallback{override fun barcodeResult(value:BarcodeResult){if(valid()){stopCamera();accept(value.text)}}})
        camera.resume()
    }
    private fun stopCamera(){wanted=false;if(::camera.isInitialized){camera.pause();camera.visibility=View.GONE}}
    private fun pickImage() {
        DiagnosticLog.event("QR", "image_picker_requested session_valid=${valid()}")
        if(!valid())return
        signSource?.cancel();signSource=null;cancelWork();stopCamera();selected=null;result.removeAllViews()
        @Suppress("DEPRECATION")
        try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),IMAGE)}
        catch (diagnosticError: Exception) { DiagnosticLog.error("QrScanActivity", diagnosticError);state.text="系统图片选择器未能打开，请尝试摄像头扫码。"}
    }
    internal fun accept(raw:String) {
        if(!valid())return
        stopCamera();selected=SignQr.parse(raw);result.removeAllViews();DiagnosticLog.event("QR", "decoded supported=${selected!=null} input_chars=${raw.length} endpoint=${DiagnosticLog.endpoint(raw)}")
        if(selected==null){state.text="未识别为支持的学习通签到二维码，请用学习通官方扫码。";return}
        val material=selected ?: return
        expectedId?.let { expected->
            if(material.activityId!=expected){selected=null;state.text="二维码与所选签到不一致，请扫描对应课程的当前二维码。";return}
            setResult(RESULT_OK,Intent().putExtra(MATERIAL,material.officialUrl));finish();return
        }
        state.text="二维码已识别，尚未提交签到。"
        val card=StudyUi.card(this,result)
        card.addView(StudyUi.text(this,"继续到应用内签到",20,true))
        card.addView(StudyUi.text(this,"先核对此账号的课程。动态码可能已刷新，可在详情页重新扫描。多账号可从关联账号的课程活动进入，选好账号后再扫码。",14))
        card.addView(StudyUi.button(this,"核对课程并继续",true){
            if(valid() && !working)resolve(material)
        })
    }
    private fun resolve(material:SignQr) {
        signSource?.cancel()
        val source=SigningApi(sessionCookies,::guard,allowQr=true);signSource=source
        val courses=sessionCourses.toList()
        work("正在核对二维码所属课程…",{
            var found:SignActivity?=null
            for(course in courses) {
                guard()
                try {found=source.activities(course).firstOrNull{it.id==material.activityId && it.kind==ReminderKind.SIGN_IN}}
                catch(e:java.util.concurrent.CancellationException){throw e}
                catch(e:LoginRequired){throw e}
                catch(e:Exception){DiagnosticLog.error("QrResolve",e)}
                if(found!=null)break
            }
            found ?: throw ApiProblem("此账号的课程中未找到进行中的对应签到。请更新课程，或从绑定账号的课程进入后扫码。")
        }){item->
            startActivity(SignActionActivity.intent(this,item,boundAccountId=requestedBoundAccountId).putExtra(MATERIAL,material.officialUrl));finish()
        }
    }

    @Deprecated("Platform document picker") override fun onActivityResult(code:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(code,resultCode,data)
        if(code==IMAGE)DiagnosticLog.event("QR", "image_picker_result ok=${resultCode==RESULT_OK} selected=${data?.data!=null} session_valid=${valid()}")
        if(code==IMAGE && resultCode==RESULT_OK && valid())data?.data?.let{uri->work("正在本机识别二维码…",{QrImage.read(this,uri)}){accept(it)}}
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        DiagnosticLog.event("Permissions", "request=$code granted=${results.count{it==PackageManager.PERMISSION_GRANTED}} total=${results.size}")
        if(code==CAMERA && valid()){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera() else {wanted=false;state.text="相机权限未开启，仍可从相册识别。"}}
    }
    override fun onResume(){super.onResume();if(wanted && ::camera.isInitialized && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera.resume()}
    override fun onPause(){if(::camera.isInitialized)camera.pause();super.onPause()}
    override fun onDestroy(){permissionDialog?.dismiss();selected=null;signSource?.cancel();stopCamera();super.onDestroy()}
    companion object {
        const val IMAGE=5401;const val CAMERA=5402;const val MATERIAL="signQrMaterial"
        fun materialIntent(activity:android.app.Activity,activeId:String,boundAccountId:String?=null)=Intent(activity,QrScanActivity::class.java)
            .putExtra("expectedId",activeId).putExtra("boundAccountId",boundAccountId)
    }
}
