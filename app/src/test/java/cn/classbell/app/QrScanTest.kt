package cn.classbell.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QrScanTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val text="https://mobilelearn.chaoxing.com/widget/sign/e?id=1700000000000&c=123456&enc=0123456789abcdef0123456789abcdef"
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(root:View,name:String)=views(root).filterIsInstance<Button>().single{it.text==name}
    private fun setup(){app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,accountKey("qr-fixture"),listOf(Course("101","201","合成课程")))}
    private fun preview(root:View,name:String){
        shadowOf(Looper.getMainLooper()).idle();root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile;val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun encodedSyntheticImageDecodesLocallyWithoutSubmittingOrOpeningOfficialPage() {
        setup();val bits=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,600,600)
        val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        for(y in 0 until 600)for(x in 0 until 600)bitmap.setPixel(x,y,if(bits[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val decoded=try{QrImage.decode(bitmap)}finally{bitmap.recycle()};assertEquals(text,decoded)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            activity.accept(decoded);assertNull(shadowOf(activity).nextStartedActivity)
            preview(root,"扫码-识别后等待确认")
            assertFalse(views(root).filterIsInstance<TextView>().any{it.text.contains("0123456789abcdef")})
            assertTrue(views(root).filterIsInstance<Button>().any{it.text=="核对课程并继续"})
            assertFalse(views(root).filterIsInstance<Button>().any{it.text=="尝试查看官方页面"})
            activity.accept("https://example.org/not-a-sign")
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.startsWith("未识别为支持")})
            assertFalse(views(root).filterIsInstance<Button>().any{it.text=="尝试查看官方页面"})
            button(root,"从相册识别二维码").performClick()
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action);assertEquals("image/*",request.intent.type)
            assertEquals(QrScanActivity.IMAGE,request.requestCode)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun accountChangeClosesTheScannerAndDiscardedResultsCannotOpenAPage() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java).setup();val activity=controller.get()
        try {
            activity.accept(text)
            app.prefs.edit().putString("identity",accountKey("other-qr-fixture")).commit();app.notifyUi();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            activity.accept(text)
            button(activity.window.decorView,"核对课程并继续").performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun materialPickerBindsActivityAndReturnsOnlyAfterAMatchingCode() {
        setup()
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","301")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup();val activity=controller.get()
        try {
            activity.accept(text);assertFalse(activity.isFinishing)
            val matching=text.replace("1700000000000","301")
            activity.accept(matching)
            assertTrue(activity.isFinishing);assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
            assertEquals(matching,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
