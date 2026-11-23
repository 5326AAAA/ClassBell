package cn.classbell.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MultiAccountSignTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val item=SignActivity("301","合成普通签到",course,otherId=0)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    private fun await(condition:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)};fail("Batch did not finish")}
    private fun scenario(unbindFirst:Boolean) {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=(1..2).map{i->accounts.bind("合成账号 $i",listOf(Cookie.Builder().name("_uid").value("90$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val sends=mutableListOf<String>()
        val queries=mutableListOf<String>()
        var confirmedReply=true
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId",item.id).putExtra("title",item.title).putExtra("otherId",0)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page)
        controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.NORMAL,"普通签到")}
            override fun attendance(activity:SignActivity):SignOutcome {
                guard();queries+=account.id
                return if(account.id==bound.last().id && confirmedReply)SignOutcome(true,"已确认签到")
                else SignOutcome(false,"尚未签到",SignResultCategory.NOT_SUBMITTED)
            }
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit();sends+=account.id
                if(account.id==bound.first().id)throw IOException("synthetic lost reply")
                return SignOutcome(true,"签到成功")
            }
            override fun cancel(){}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            if(unbindFirst)accounts.unbind(bound.first().id)
            button("确认所选账号并提交").performClick()
            val confirmation=ShadowAlertDialog.getLatestAlertDialog()
            val panel=views(confirmation.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
            assertTrue(panel.contains(course.name));assertTrue(panel.contains(item.title))
            assertTrue(panel.contains(if(unbindFirst)"将提交 · 1" else "将提交 · 2"))
            if(unbindFirst){assertTrue(panel.contains("将跳过 · 1"));assertTrue(panel.contains("账号已更新或解绑"))}
            assertTrue(sends.isEmpty())
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{text().contains("处理结束") && text().contains("成功 · 签到成功")}
            if(unbindFirst){assertEquals(listOf(bound.last().id),sends);assertTrue(text().contains("解绑"))}
            else {
                assertEquals(bound.map{it.id},sends);assertTrue(text().contains("结果待核实"))
                root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
                val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
                val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
                val out=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
                File(out,"多账号签到-逐个结果.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                assertFalse(button("确认所选账号并提交").isEnabled)
                button("刷新所选账号的签到结果").performClick()
                await{text().contains("处理结束") && text().contains("已确认签到")}
                assertEquals(bound.map{it.id},queries)
                assertTrue(text().contains("尚未签到"))
                assertTrue(text().contains("结果待核实"))
                confirmedReply=false
                button("刷新所选账号的签到结果").performClick()
                await{text().contains("处理结束") && queries.size==4}
                assertTrue(text().contains("成功 · 已确认签到"))
                assertFalse(button("确认所选账号并提交").isEnabled)
                assertEquals(2,sends.size)
            }
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun oneLostReplyDoesNotBlockOtherAccountsAndNeitherAccountIsSentTwice()=scenario(false)
    @Test fun accountUnboundAfterPreflightIsSkippedAndOtherAccountContinues()=scenario(true)
}
