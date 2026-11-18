package cn.classbell.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
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
import org.json.JSONObject
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StudyScreensTest {
    @Test fun reminderAndHomeworkPickersKeepChosenCoursesFirstAfterTogglingSearchingAndSaving(){
        val courses=(1..24).map{Course((100+it).toString(),(200+it).toString(),"合成课程$it")}
        val selected=courses.takeLast(3).map{it.key}.toSet()
        for(homework in listOf(false,true)) {
            app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,accountKey("order-fixture"),courses);app.saveSelectedCourses(selected)
            val owner=app.prefs.getString("identity","").orEmpty();HomeworkCourseFilter(app.prefs).save(owner,selected)
            val intent=Intent(app,CoursePickerActivity::class.java).apply{if(homework)putExtra("mode","homework")}
            val controller=Robolectric.buildActivity(CoursePickerActivity::class.java,intent).setup()
            try{
                val root=controller.get().window.decorView
                fun checks()=views(root).filterIsInstance<CheckBox>()
                assertEquals(courses.takeLast(3).map{it.name},checks().take(3).map{it.text.toString()})
                checks().first().performClick();shadowOf(Looper.getMainLooper()).idle()
                assertEquals(courses.takeLast(2).map{it.name},checks().take(2).map{it.text.toString()})
                assertTrue(checks().take(2).all{it.isChecked});assertTrue(checks().drop(2).none{it.isChecked})
                val query=views(root).filterIsInstance<android.widget.EditText>().single();query.setText("合成课程2")
                assertTrue(checks().takeWhile{it.isChecked}.size==2);query.setText("")
                preview(root,if(homework)"作业选课-已选优先" else "提醒选课-已选优先")
                views(root).filterIsInstance<Button>().single{it.text=="保存选择"}.performClick()
                val expected=courses.takeLast(2).map{it.key}.toSet()
                assertEquals(expected,if(homework)HomeworkCourseFilter(app.prefs).selection(owner) else app.selectedCourses().map{it.key}.toSet())
                if(homework)assertEquals(selected,app.selectedCourses().map{it.key}.toSet())
            }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
        }
    }
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","数据结构与算法","合成教师","软件工程一班","301")
    private val sign=SignActivity("401","合成位置签到",course,otherId=4)
    private fun setup() {app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,accountKey("study-screen"),listOf(course,Course("102","202","学术英语")))}
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun labels(root:View)=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
    private fun await(test:()->Boolean){val deadline=System.nanoTime()+5_000_000_000;while(System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};fail("Timed out waiting for synthetic UI response")}
    private fun preview(root:View,name:String){
        shadowOf(Looper.getMainLooper()).idle();root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844);shadowOf(Looper.getMainLooper()).idle()
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
        val cwd=File(System.getProperty("user.dir").orEmpty());val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()};File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun courseHistoryAndLocationScreensRenderWithoutAnyRealAccountOrSubmission() {
        setup()
        val detail=Robolectric.buildActivity(CourseDetailActivity::class.java,Intent(app,CourseDetailActivity::class.java).putExtra("courseKey",course.key))
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val endedSign=sign.copy(id="402",title="合成历史签到",otherId=2)
        detail.get().feedSource={listOf(
            CourseActivity(sign,2,1,"进行中"),
            CourseActivity(if(reads.incrementAndGet()==1)endedSign else endedSign.copy(title="更新后的历史签到"),2,2,"结束时间：10-09 23:37"),
            CourseActivity(sign.copy(id="403",title="合成分组通知"),42,2,"结束时间：10-09 23:37")
        )}
        val activity=detail.setup().get()
        try {
            await{labels(activity.window.decorView).contains("合成历史签到")}
            val resultLabels=labels(activity.window.decorView)
            assertTrue(resultLabels.contains("已结束签到 · ${course.name}"))
            assertFalse(resultLabels.contains(sign.title))
            assertFalse(resultLabels.contains("合成分组通知"))
            val list=CourseListPanel(activity,app,{}, {})
            assertFalse(views(list).filterIsInstance<Button>().any{it.text=="查看签到"})
            views(list).single{it.contentDescription=="进入课程：${course.name}"}.performClick()
            assertEquals(course.key,shadowOf(activity).nextStartedActivity.getStringExtra("courseKey"))
            val historyButtons=views(activity.window.decorView).filterIsInstance<Button>()
            assertFalse(historyButtons.any{it.text in listOf("查看签到","全部活动","已结束签到")})
            preview(activity.window.decorView,"课程详情-签到结果")
            historyButtons.single{it.text=="更新签到与课堂活动"}.performClick()
            await{labels(activity.window.decorView).contains("更新后的历史签到")}
            val refreshed=labels(activity.window.decorView)
            assertTrue(refreshed.contains(sign.title))
            assertTrue(refreshed.indexOf("查看签到结果") in 0 until refreshed.indexOf("合成分组通知"))
            val refreshedButtons=views(activity.window.decorView).filterIsInstance<Button>()
            refreshedButtons.single{it.text=="查看签到结果"}.performClick()
            val history=shadowOf(activity).nextStartedActivity
            assertEquals(SignActionActivity::class.java.name,history.component?.className)
            assertEquals("402",history.getStringExtra("activeId"))
            assertTrue(history.getBooleanExtra("historyOnly",false))
            val tabs=historyButtons.filter{it.text in listOf("签到结果","课程作业","课程内容")}
            assertEquals(listOf("签到结果"),tabs.filter{it.isSelected}.map{it.text.toString()})
            for(title in listOf("课程作业","课程内容")) {
                tabs.single{it.text==title}.performClick()
                assertEquals(listOf(title),tabs.filter{it.isSelected}.map{it.text.toString()})
                assertTrue(tabs.single{it.text==title}.contentDescription.endsWith("已选择"))
                preview(activity.window.decorView,"课程详情-$title-选中")
            }
        }finally{detail.pause().stop().destroy()}
        val manual=Robolectric.buildActivity(SignActionActivity::class.java,SignActionActivity.intent(activity,sign))
        manual.get().sourceFactory={guard->object:SignSource{
            override fun activities(course:Course)=listOf(sign)
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.LOCATION,"使用手机当前定位，由学习通检查位置要求。")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome=throw AssertionError("No synthetic attendance submission was requested")
            override fun cancel(){}
        }}
        val location=manual.setup().get()
        try{await{labels(location.window.decorView).contains("手机当前位置")};preview(location.window.decorView,"位置签到-未授权状态");assertTrue(labels(location.window.decorView).contains("获取 / 更新当前位置"))}
        finally{manual.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun officialHomeworkChooserReturnsOnlySelectedFilesAndDiscardsResultsAfterAccountChange() {
        setup()
        val controller=Robolectric.buildActivity(OfficialPageActivity::class.java,Intent(app,OfficialPageActivity::class.java).putExtra("url",HomeworkParser.LIST_URL).putExtra("title","合成作业详情"))
        val activity=controller.setup().get()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val browser=views(activity.window.decorView).filterIsInstance<WebView>().single();browser.loadUrl(HomeworkParser.LIST_URL)
            val parameters=object:WebChromeClient.FileChooserParams(){
                override fun getMode()=MODE_OPEN
                override fun getAcceptTypes()=arrayOf("application/pdf")
                override fun isCaptureEnabled()=false
                override fun getTitle():CharSequence?=null
                override fun getFilenameHint():String?=null
                override fun createIntent()=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf")
            }
            var received:List<Uri>?=null
            assertTrue(browser.webChromeClient!!.onShowFileChooser(browser,ValueCallback{received=it?.toList()},parameters))
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
            assertEquals(OfficialPageActivity.FILE_REQUEST,request.requestCode)
            val uri=Uri.parse("content://synthetic/selected.pdf")
            OfficialPageActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}
                .invoke(activity,OfficialPageActivity.FILE_REQUEST,Activity.RESULT_OK,Intent().setData(uri))
            assertEquals(listOf(uri),received)
            var cancelled=false
            browser.webChromeClient!!.onShowFileChooser(browser,ValueCallback{cancelled=it==null},parameters)
            app.prefs.edit().putString("identity",accountKey("another-account")).commit();app.notifyUi();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cancelled)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun qrRedirectReplacesBlankWebViewWithRecoveryActions() {
        setup()
        val qr = "https://mobilelearn.chaoxing.com/widget/sign/e?id=1&c=2&enc=${"a".repeat(32)}"
        val controller = Robolectric.buildActivity(OfficialPageActivity::class.java,
            Intent(app, OfficialPageActivity::class.java).putExtra("url", qr).putExtra("title", "扫码签到"))
        val activity = controller.setup().get()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            val browser = views(root).filterIsInstance<WebView>().single()
            @Suppress("DEPRECATION")
            val blocked = requireNotNull(browser.webViewClient).shouldOverrideUrlLoading(browser, "chaoxing://synthetic")
            assertTrue(blocked)
            assertEquals(View.GONE, browser.visibility)
            assertTrue(labels(root).contains("二维码已识别"))
            views(root).filterIsInstance<Button>().single { it.text == "返回扫码页" }.performClick()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy(); app.prefs.edit().clear().commit() }
    }
    @Test fun homeworkCoursePickerDoesNotChangeTheMonitoringCourseSelection() {
        setup();val original=app.selectedCourses().map{it.key}.toSet()
        val controller=Robolectric.buildActivity(CoursePickerActivity::class.java,Intent(app,CoursePickerActivity::class.java).putExtra("mode","homework"))
        val activity=controller.setup().get()
        try {
            val checks=views(activity.window.decorView).filterIsInstance<CheckBox>();assertEquals(2,checks.size)
            checks.first().performClick()
            preview(activity.window.decorView,"独立作业课程选择")
            views(activity.window.decorView).filterIsInstance<Button>().single{it.text=="保存选择"}.performClick()
            assertEquals(original,app.selectedCourses().map{it.key}.toSet())
            assertEquals(setOf("102:202"),HomeworkCourseFilter(app.prefs).selection(app.prefs.getString("identity","").orEmpty()))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun officialClientNavigationLoadsAllowedTargetAndDoesNotEraseBlockedFeedback() {
        setup()
        val controller=Robolectric.buildActivity(OfficialPageActivity::class.java,Intent(app,OfficialPageActivity::class.java).putExtra("url",HomeworkParser.LIST_URL).putExtra("title","作业详情"))
        val activity=controller.setup().get()
        try {
            val browser=views(activity.window.decorView).filterIsInstance<WebView>().single();browser.loadUrl(HomeworkParser.LIST_URL)
            assertEquals(17,OfficialPagePolicy.bridgeId("jsbridge://PostNotificationWithId-17"))
            assertNull(OfficialPagePolicy.bridgeId("jsbridge://PostNotificationWithId-17;alert(1)"))
            val target="https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?workId=synthetic"
            val item=JSONObject().put("name","CLIENT_OPEN_URL").put("userInfo",JSONObject().put("webUrl",target))
            val encoded=JSONObject.quote(item.toString())
            activity.handleNotification(OfficialPagePolicy.notification(encoded)!!)
            assertEquals(target,shadowOf(browser).lastLoadedUrl)
            browser.loadUrl(HomeworkParser.LIST_URL)
            activity.handleNotification(JSONObject().put("name","CLIENT_OPEN_URL").put("userInfo",JSONObject().put("webUrl","javascript:alert(1)")))
            assertEquals(HomeworkParser.LIST_URL,shadowOf(browser).lastLoadedUrl)
            browser.webViewClient!!.onPageFinished(browser,HomeworkParser.LIST_URL)
            assertTrue(labels(activity.window.decorView).contains("此内容需要在学习通中打开"))
            assertFalse(labels(activity.window.decorView).contains("可选择文件、编辑并自行提交"))
            preview(activity.window.decorView,"官方页面-返回栏与错误提示")
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun selectingTagRemainsLocalUntilPickerSave() {
        setup();app.saveSelectedCourses(setOf(course.key))
        val identity=app.prefs.getString("identity","").orEmpty()
        app.courseMemory.putTag(identity,"本学期",setOf("102:202"))
        val controller=Robolectric.buildActivity(CoursePickerActivity::class.java).setup();val activity=controller.get()
        try {
            views(activity.window.decorView).filterIsInstance<Button>().single{it.text=="按标签"}.performClick()
            val sheet=ShadowAlertDialog.getLatestAlertDialog()
            val check=views(sheet.window!!.decorView).filterIsInstance<CheckBox>().single();check.performClick()
            preview(sheet.window!!.decorView,"标签选择-底部面板")
            views(sheet.window!!.decorView).filterIsInstance<Button>().single{it.tag==android.app.AlertDialog.BUTTON_POSITIVE}.performClick()
            assertEquals(setOf(course.key),app.selectedCourses().map{it.key}.toSet())
            preview(activity.window.decorView,"独立课程选择-标签未保存")
            views(activity.window.decorView).filterIsInstance<Button>().single{it.text=="保存选择"}.performClick()
            assertEquals(setOf("102:202"),app.selectedCourses().map{it.key}.toSet())
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
