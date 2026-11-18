package cn.classbell.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BoundNativeSignTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val sign=SignActivity("301","合成普通签到",course,otherId=0)
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun await(condition:()->Boolean) {
        val end=System.nanoTime()+5_000_000_000
        while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)}
        fail("Timed out waiting for native form")
    }
    @Test fun boundAccountCanUseNativeFormWhileMainAccountIsLoggedOutAndRecordsOnlyItsOwnCommit() {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=accounts.bind("合成绑定账号",listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build()),listOf(course))
        var commits=0
        val page=Intent(app,SignActionActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId",sign.id).putExtra("title",sign.title)
            .putExtra("otherId",0).putExtra("boundAccountId",bound.id).putExtra("monitoredPhoto",true)
        val controller=Robolectric.buildActivity(SignActionActivity::class.java,page)
        controller.get().boundAccountRepository=accounts
        controller.get().sourceFactory={guard->object:SignSource {
            override fun activities(course:Course)=listOf(sign)
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.NORMAL,"普通签到")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome{guard();beforeCommit();commits++;return SignOutcome(true,"签到成功")}
            override fun cancel(){}
        }}
        val activity=controller.setup().get()
        try {
            fun buttons()=views(activity.window.decorView).filterIsInstance<Button>()
            await{buttons().any{it.text=="确认并提交签到"}}
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains(bound.label)})
            assertFalse(buttons().any{it.text=="打开学习通签到页面"})
            buttons().single{it.text=="确认并提交签到"}.performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{views(activity.window.decorView).filterIsInstance<TextView>().any{it.text=="签到成功"}}
            assertEquals(1,commits);assertTrue(app.signing.ledger.attempted(bound.id,sign));assertFalse(app.signing.ledger.attempted("",sign))
        }finally{controller.pause().stop().destroy();accounts.unbind(bound.id);app.prefs.edit().clear().commit()}
    }
    @Test fun missingBoundAccountNeverFallsBackToMainAccount() {
        app.prefs.edit().clear().putBoolean("loggedIn",true).putString("identity","main-account").commit()
        val page=Intent(app,SignActionActivity::class.java).putExtra("boundAccountId","missing").putExtra("courseKey",course.key).putExtra("activeId",sign.id)
        val controller=Robolectric.buildActivity(SignActionActivity::class.java,page)
        controller.get().sourceFactory={fail("Must not create a sign source for the main account");throw AssertionError()}
        try {assertTrue(controller.setup().get().isFinishing)}finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
