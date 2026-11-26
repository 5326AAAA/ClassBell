package cn.classbell.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NewToolsUiTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun await(condition:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){shadowOf(android.os.Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)};fail("UI work did not finish")}
    private fun capture(root:View,name:String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun updatePageShowsNativeReleaseWithoutAutomaticNetworkOrDowngradeButton() {
        val controller=Robolectric.buildActivity(UpdateActivity::class.java).setup()
        try {
            val activity=controller.get()
            activity.render(AppRelease(AppVersion(1,3,3),"v1.3.3","合成更新说明",HelpUi.PROJECT_URL+"/releases/tag/v1.3.3",null),"合成检查完成")
            val root=activity.window.decorView
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("高于 GitHub")})
            assertFalse(views(root).filterIsInstance<Button>().any{it.text=="用浏览器下载安装包"})
            capture(root,"原生检查更新.png")
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun calendarShowsPendingItemsAndLogoutDisablesExport() {
        app.prefs.edit().putString("identity","synthetic-calendar").putBoolean("loggedIn",true).commit()
        val controller=Robolectric.buildActivity(HomeworkCalendarActivity::class.java,Intent(app,HomeworkCalendarActivity::class.java)).setup()
        try {
            val activity=controller.get();val now=System.currentTimeMillis()
            activity.snapshotOverride=HomeworkSnapshot(now,listOf(HomeworkItem("1","实验报告","合成物理课程","101:201","未提交","",now+86_400_000,now,false,"")))
            activity.render()
            val root=activity.window.decorView
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text=="1 项待交作业"})
            assertTrue(views(root).filterIsInstance<Button>().single{it.text=="保存日历文件 (.ics)"}.isEnabled)
            capture(root,"作业截止日历.png")
            app.prefs.edit().putBoolean("loggedIn",false).commit();activity.render()
            assertFalse(views(root).filterIsInstance<Button>().single{it.text=="保存日历文件 (.ics)"}.isEnabled)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    private fun exportScenario(switchAccount:Boolean) {
        app.prefs.edit().putString("identity","synthetic-calendar").putBoolean("loggedIn",true).commit()
        val controller=Robolectric.buildActivity(HomeworkCalendarActivity::class.java).setup()
        try {
            val activity=controller.get();val now=System.currentTimeMillis()
            activity.snapshotOverride=HomeworkSnapshot(now,listOf(HomeworkItem("1","合成作业","合成课程","a","未提交","",now+3_600_000,now,false,"https://example.invalid/?session=private")))
            activity.render()
            views(activity.window.decorView).filterIsInstance<Button>().single{it.text=="保存日历文件 (.ics)"}.performClick()
            val field=HomeworkCalendarActivity::class.java.getDeclaredField("pending").apply{isAccessible=true}
            await{field.get(activity)!=null}
            val file=field.get(activity) as File
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.action);assertEquals("text/calendar",request.intent.type)
            val uri=android.net.Uri.parse("content://test/export-calendar")
            val output=java.io.ByteArrayOutputStream();shadowOf(app.contentResolver).registerOutputStream(uri,output)
            if(switchAccount)app.prefs.edit().putString("identity","other-synthetic-account").commit()
            shadowOf(activity).receiveResult(request.intent,android.app.Activity.RESULT_OK,Intent().setData(uri))
            await{!file.exists()}
            if(switchAccount)assertEquals(0,output.size()) else {
                assertTrue(output.toString("UTF-8").contains("END:VCALENDAR"));assertFalse(output.toString("UTF-8").contains("session=private"))
            }
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun calendarUsesSystemDocumentPickerAndWritesActualIcs()=exportScenario(false)
    @Test fun switchingAccountWhileFilePickerIsOpenCancelsExportAndDeletesPrivateFile()=exportScenario(true)
}
