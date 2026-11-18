package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import okhttp3.Cookie
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
class NativeMaterialUiTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val raw="https://mobilelearn.chaoxing.com/widget/sign/e?id=301&enc=FACDD34DBC291EE176"
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    private fun await(condition:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)};fail("Native form did not finish")}
    private fun preview(root:View,name:String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val bitmap=android.graphics.Bitmap.createBitmap(390,844,android.graphics.Bitmap.Config.ARGB_8888);root.draw(android.graphics.Canvas(bitmap))
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val out=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        File(out,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun batch(mode:SignMode) {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=(1..2).map{i->accounts.bind("合成账号 $i",listOf(Cookie.Builder().name("_uid").value("90$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val sends=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val overlap=java.util.concurrent.CountDownLatch(2)
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId","301").putExtra("title","合成签到").putExtra("otherId",if(mode==SignMode.QR)2 else 3)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page)
        controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=emptyList<SignActivity>()
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(mode,"本次 $mode")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit)=throw AssertionError("Wrong route")
            override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
                guard();qr.requireFor(activity);assertEquals(SignMode.QR,mode);beforeCommit();sends+=account.id
                overlap.countDown();assertTrue("QR account requests must overlap",overlap.await(3,java.util.concurrent.TimeUnit.SECONDS));return SignOutcome(true,"签到成功")
            }
            override fun submitGesture(activity:SignActivity,path:String,beforeCommit:()->Unit):SignOutcome {
                guard();assertEquals("258",path);assertEquals(SignMode.GESTURE,mode);beforeCommit();sends+=account.id;return SignOutcome(true,"签到成功")
            }
            override fun cancel(){}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            if(mode==SignMode.QR) {
                button("扫描 / 相册识别本次二维码").performClick()
                val request=shadowOf(activity).nextStartedActivityForResult
                assertEquals(QrScanActivity::class.java.name,shadowOf(activity).nextStartedActivity.component!!.className)
                assertEquals(QrScanActivity::class.java.name,request.intent.component!!.className)
                assertEquals(bound.first().id,request.intent.getStringExtra("boundAccountId"))
                assertEquals("301",request.intent.getStringExtra("expectedId"))
                shadowOf(activity).receiveResult(request.intent,Activity.RESULT_OK,Intent().putExtra(QrScanActivity.MATERIAL,raw))
            } else views(root).filterIsInstance<EditText>().single().setText("258")
            preview(root,"多账号-$mode-准备完成")
            button("确认所选账号并提交").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{text().contains("处理结束") && text().split("成功 · 签到成功").size==3}
            assertEquals(bound.map{it.id}.toSet(),sends.toSet());assertEquals(2,sends.size)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun batchQrReturnsToNativeFormAndSubmitsBothBoundAccounts()=batch(SignMode.QR)
    @Test fun batchGestureUsesProvidedPathForEachBoundAccount()=batch(SignMode.GESTURE)
    @Test fun boundScannerWorksWithoutMainLoginAndClosesAfterAccountRemoval() {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=accounts.bind("合成账号",listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build()),listOf(course))
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","301").putExtra("boundAccountId",bound.id)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page);controller.get().boundAccountRepository=accounts
        val activity=controller.setup().get()
        try {
            assertFalse(activity.isFinishing)
            accounts.unbind(bound.id);app.notifyUi();shadowOf(Looper.getMainLooper()).idle();activity.accept(raw)
            assertTrue(activity.isFinishing);assertEquals(Activity.RESULT_CANCELED,shadowOf(activity).resultCode)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun stopCancelsThreeParallelRequestsAndDoesNotStartQueuedAccounts() {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        repeat(5){i->accounts.bind("合成账号 $i",listOf(Cookie.Builder().name("_uid").value("90$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val entered=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val sent=java.util.concurrent.atomic.AtomicInteger();val finished=java.util.concurrent.atomic.AtomicInteger()
        val gate=java.util.concurrent.CountDownLatch(1)
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId","301").putExtra("title","合成扫码").putExtra("otherId",2)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page);controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=emptyList<SignActivity>()
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.QR,"扫码")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit)=throw AssertionError()
            override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
                entered+=account.id
                try{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);guard();beforeCommit();sent.incrementAndGet();return SignOutcome(true,"成功")}
                finally{finished.incrementAndGet()}
            }
            override fun cancel(){if(entered.isNotEmpty())gate.countDown()}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(name:String)=views(root).filterIsInstance<Button>().single{it.text==name}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            button("扫描 / 相册识别本次二维码").performClick()
            val request=shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(request.intent,Activity.RESULT_OK,Intent().putExtra(QrScanActivity.MATERIAL,raw))
            button("确认所选账号并提交").performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{entered.size==3}
            button("停止后续账号").performClick();await{finished.get()==3}
            assertEquals(3,entered.size);assertEquals(0,sent.get());assertTrue(text().contains("已停止后续处理"))
        }finally{gate.countDown();controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
