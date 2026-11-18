package cn.classbell.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BoundAccountsLayoutTest {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun capture(root:View,name:String) {
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY))
        root.layout(0,0,390,844);shadowOf(Looper.getMainLooper()).idle()
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun bindingAndProxyPagesUseChosenAccountAndCourseWithoutStartingRequestsOrStoppingMonitoring() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        app.prefs.edit().clear().putBoolean("monitorEnabled",true).commit()
        var json:String?=null;val store=BoundAccounts({json},{json=it})
        for((uid,label) in listOf("901" to "我的学生账号","902" to "已授权的测试账号"))store.bind(label,
            listOf(Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build()),listOf(Course("101","201","数据结构与算法")))
        val controller=Robolectric.buildActivity(BoundAccountsActivity::class.java)
        controller.get().accountRepository=store
        val activity=controller.setup().get();val root=activity.window.decorView
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        try {
            capture(root,"账号绑定-合成数据")
            val scroll=views(root).filterIsInstance<ScrollView>().single()
            scroll.fullScroll(View.FOCUS_DOWN);capture(root,"账号绑定-添加表单")
            views(root).filterIsInstance<Button>().first{it.text=="使用此账号代签"}.performClick()
            scroll.scrollTo(0,0)
            button("选择课程 · 未选择").performClick()
            ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0)
            capture(root,"代签-合成数据")
            val labels=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
            assertTrue(labels.contains("选择账号 · 我的学生账号"));assertTrue(labels.contains("选择课程 · 数据结构与算法"))
            assertTrue(app.prefs.getBoolean("monitorEnabled",false))
            assertNull(shadowOf(activity).nextStartedService)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
