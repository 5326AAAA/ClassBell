package cn.classbell.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModernLayoutTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private var controller: ActivityController<MainActivity>? = null
    @Before fun setup() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
        val courses = listOf(Course("101", "201", "线性代数"), Course("102", "202", "数据结构与算法"), Course("103", "203", "WEB应用系统实践"))
        app.courseMemory.activate(app.prefs, accountKey("preview-fixture"), courses)
        app.saveSelectedCourses(courses.take(2).map { it.key }.toSet())
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun main() = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(activity: MainActivity, text: String):View = views(activity.window.decorView)
        .single { (it is Button && it.text.toString() == text) || it.contentDescription==text }
    private fun layout(root: View, width: Int = 390, height: Int = 844) {
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun preview(view: View, name: String) {
        val cwd = File(System.getProperty("user.dir").orEmpty())
        val project = if (File(cwd, "app/src").exists()) cwd else cwd.parentFile
        val output = File(project, "outputs/ui-v${BuildConfig.VERSION_NAME}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(ModernUi.background); view.draw(canvas)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun helpMenuOpensChangelogCoveringEveryRetainedApkAndHeaderKeepsIdentity() {
        val activity = main(); layout(activity.window.decorView)
        val text = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(text.contains("作者：Forest5326"))
        assertTrue(text.contains("v${BuildConfig.VERSION_NAME}"))
        assertTrue(text.contains("签到，及时知道。"))
        assertEquals(BuildConfig.VERSION_NAME, ReleaseNotes.entries.first().version)
        val cwd = File(System.getProperty("user.dir").orEmpty())
        val project = if (File(cwd, "app/src").exists()) cwd else cwd.parentFile
        val apkVersions = File(project, "outputs").listFiles().orEmpty().mapNotNull {
            Regex("ClassBell-v(\\d+\\.\\d+\\.\\d+)-debug\\.apk").matchEntire(it.name)?.groupValues?.get(1)
        }
        assertTrue(ReleaseNotes.entries.map { it.version }.containsAll(apkVersions))
        button(activity, "☰ 使用帮助").performClick()
        val sheet=ShadowAlertDialog.getLatestAlertDialog()
        layout(sheet.window!!.decorView,358,650);preview(sheet.window!!.decorView,"使用帮助")
        views(sheet.window!!.decorView).single{it.contentDescription=="更新日志"}.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val message = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n"){it.text}
        assertTrue(message.contains("0.3.0")); assertTrue(message.contains("0.2.0"))
        val root = dialog.window!!.decorView
        layout(root, 358, 650); preview(root, "更新日志")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle(); assertFalse(dialog.isShowing)
    }

    @Test fun fourPrimaryPagesKeepToolsUnderProfileWithoutStoppingMonitor() {
        val activity = main()
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        val expected = listOf("课堂提醒页面", "课程页面", "作业页面", "我的页面")
        listOf("首页", "课程", "作业", "我的").forEachIndexed { index, title ->
            button(activity, title).performClick(); layout(activity.window.decorView)
            val visible = views(activity.window.decorView).filterIsInstance<ScrollView>().filter { it.visibility == View.VISIBLE }
            assertEquals(listOf(expected[index]), visible.map { it.contentDescription.toString() })
            assertTrue(app.prefs.getBoolean("monitorEnabled", false))
            val nav = button(activity, title)
            assertTrue(nav.isSelected)
            assertEquals(View.GONE,button(activity,"返回").visibility)
            val author=views(activity.window.decorView).filterIsInstance<TextView>().single{it.text=="作者：Forest5326"}
            assertEquals(if(index==0)View.VISIBLE else View.GONE,(author.parent as View).visibility)
            if(title=="我的") assertProfileTools(activity)
            assertTrue(nav.height >= ModernUi.dp(activity, 48))
            preview(activity.window.decorView, title)
        }
        for(title in listOf("提醒设置","测试与诊断","账号中心 / 切换账号","签到中心")) {
            button(activity,"我的").performClick()
            if(title=="测试与诊断") {
                button(activity,"更多工具").performClick()
                assertEquals("tools",app.prefs.getString("ui.route",""))
                layout(activity.window.decorView)
                preview(activity.window.decorView,"更多工具")
            }
            button(activity,title).performClick();layout(activity.window.decorView);preview(activity.window.decorView,title.replace(" / ","-"))
            assertTrue(button(activity,"我的").isSelected)
            assertTrue(app.prefs.getBoolean("monitorEnabled",false))
            if(title=="测试与诊断") {
                button(activity,"预览无签名签到链接").performClick()
                val previewDialog=ShadowAlertDialog.getLatestAlertDialog()
                val labels=views(previewDialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n"){it.text}
                assertTrue(labels.contains("https://example.invalid/attendance/preview?activity=demo-001"))
                assertTrue(listOf("puid","openid","wxsn","_wtime").none{it in labels})
                layout(previewDialog.window!!.decorView,358,320)
                preview(previewDialog.window!!.decorView,"无签名签到链接预览")
                previewDialog.dismiss()
            }
        }
        button(activity,"我的").performClick();button(activity,"提醒设置").performClick()
        app.prefs.edit().putBoolean("monitorEnabled", false).commit()
        controller!!.recreate()
        assertTrue(button(controller!!.get(), "我的").isSelected)
        controller!!.get().onBackPressed()
        assertEquals("profile",app.prefs.getString("ui.route",""))
    }

    @Test fun courseWindowKeepsNativeSelectionAndReadableActionLayout() {
        val activity = main()
        button(activity,"我的").performClick();button(activity,"提醒课程").performClick()
        val intent=shadowOf(activity).nextStartedActivity
        assertEquals(CoursePickerActivity::class.java.name,intent.component!!.className)
        val picker=Robolectric.buildActivity(CoursePickerActivity::class.java,intent).setup()
        try {
            val root=picker.get().window.decorView;layout(root,358,720);preview(root,"独立课程选择")
            val checks=views(root).filterIsInstance<android.widget.CheckBox>();assertEquals(3,checks.size);assertEquals(2,checks.count{it.isChecked})
            views(root).filterIsInstance<Button>().single{it.text=="保存选择"}.performClick();shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2,app.selectedCourses().size)
        }finally{picker.pause().stop().destroy()}
    }

    @Test fun courseFiltersHaveAnExplicitSelectedStateAndDialogDoesNotResizeAfterOpening() {
        val activity=main();button(activity,"课程").performClick()
        assertTrue(button(activity,"全部课程").isSelected);assertFalse(button(activity,"仅已监测").isSelected)
        button(activity,"仅已监测").performClick()
        assertTrue(button(activity,"仅已监测").isSelected);assertFalse(button(activity,"全部课程").isSelected)
        layout(activity.window.decorView);preview(activity.window.decorView,"课程-已监测选中")
        val dialog=HelpUi.content(activity,"使用指南","合成标题\n合成说明")
        val before=dialog.window!!.attributes.let{listOf(it.width,it.height,it.x,it.y,it.gravity,it.windowAnimations)}
        layout(dialog.window!!.decorView,358,650);shadowOf(Looper.getMainLooper()).idle()
        val after=dialog.window!!.attributes.let{listOf(it.width,it.height,it.x,it.y,it.gravity,it.windowAnimations)}
        assertEquals(before,after);assertEquals(0,dialog.window!!.attributes.windowAnimations)
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<ScrollView>().all{!it.isVerticalScrollBarEnabled})
        dialog.dismiss()
    }

    @Test fun accountSwitchingUsesCompactRowsWithSeparateAddAndManagementActions() {
        var stored:String?=null
        val repository=BoundAccounts({stored},{stored=it})
        fun cookie(uid:String)=okhttp3.Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build()
        val courses=app.courses()
        repository.bind("当前学习账号",listOf(cookie("701")),courses)
        repository.bind("另一个学习账号",listOf(cookie("702")),courses.take(1))
        app.courseMemory.activate(app.prefs,accountKey("701"),courses)
        val instance=Robolectric.buildActivity(MainActivity::class.java)
        instance.get().savedAccountRepository=repository;controller=instance
        val activity=instance.setup().get()
        button(activity,"我的").performClick();button(activity,"账号中心 / 切换账号").performClick();layout(activity.window.decorView)
        assertTrue(views(activity.window.decorView).any{it.contentDescription=="当前账号：当前学习账号"})
        assertTrue(views(activity.window.decorView).any{it.contentDescription=="切换账号：另一个学习账号"})
        val current=views(activity.window.decorView).single{it.contentDescription=="当前账号：当前学习账号"}
        val other=views(activity.window.decorView).single{it.contentDescription=="切换账号：另一个学习账号"}
        assertTrue(other.top-current.bottom>=ModernUi.dp(activity,12))
        val badge=views(current).filterIsInstance<TextView>().single{it.text=="已登录"}
        assertEquals(current.height/2f,badge.top+badge.height/2f,1f)
        assertEquals(android.view.Gravity.CENTER,badge.gravity)
        assertFalse(views(activity.window.decorView).filterIsInstance<TextView>().any{it.text=="当前学习通账号"})
        preview(activity.window.decorView,"切换账号-双账号列表")
        button(activity,"管理账号：当前学习账号").performClick()
        val menu=ShadowAlertDialog.getLatestAlertDialog()
        val root=menu.window!!.decorView;layout(root,358,450)
        assertNull(menu.listView)
        assertEquals(android.view.Gravity.BOTTOM,menu.window!!.attributes.gravity)
        preview(root,"账号操作-底部面板")
        views(root).single{it.contentDescription=="移除保存的登录"}.performClick()
        val confirmation=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(confirmation.isShowing);confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(2,repository.list().size)
        button(activity,"添加账号").performClick();layout(activity.window.decorView)
        val password=views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single{it.hint=="学习通密码"}
        assertEquals(View.VISIBLE,(password.parent as View).visibility)
        preview(activity.window.decorView,"切换账号-添加登录")
    }

    @Test fun primaryPagesHideBackAndCreditsWhileSecondaryPageKeepsReturn() {
        val activity=main();button(activity,"我的").performClick()
        assertEquals(View.GONE,button(activity,"返回").visibility)
        val author=views(activity.window.decorView).filterIsInstance<TextView>().single{it.text=="作者：Forest5326"}
        assertEquals(View.GONE,(author.parent as View).visibility)
        button(activity,"账号中心 / 切换账号").performClick()
        assertEquals(View.VISIBLE,button(activity,"返回").visibility)
        button(activity,"返回").performClick();assertEquals("profile",app.prefs.getString("ui.route",""))
        activity.onBackPressed();assertEquals("home",app.prefs.getString("ui.route",""))
        assertEquals(View.GONE,button(activity,"返回").visibility)
        assertEquals(View.VISIBLE,(author.parent as View).visibility)
    }

    @Test fun helpPromptOnlyAppearsOnTheFirstLaunch() {
        val activity=main();val prompt=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(prompt.isShowing)
        assertTrue(views(prompt.window!!.decorView).filterIsInstance<TextView>().any{it.text=="请查看使用帮助"})
        layout(prompt.window!!.decorView,358,220);preview(prompt.window!!.decorView,"首次打开-使用帮助提示")
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        controller!!.recreate()
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }
    @Test fun previousFourTabIndexMigratesToSamePage() {
        app.prefs.edit().putInt("ui.page", 2).commit()
        val activity = main()
        assertTrue(button(activity, "我的").isSelected)
        assertEquals("test", app.prefs.getString("ui.route", null))
        button(activity, "作业").performClick()
        controller!!.recreate()
        assertTrue(button(controller!!.get(), "作业").isSelected)
    }

    @Test fun homeworkPageRendersPopulatedNativeCards() {
        val activity = main()
        val now = System.currentTimeMillis()
        val records = HomeworkSnapshot(now, listOf(
            HomeworkItem("fixture1", "二叉树与遍历练习", "数据结构与算法", "102:202", "未提交", "剩余2天3小时", now + 183_600_000,
                now, false, "https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/task?courseId=102&classId=202&workId=fixture1"),
            HomeworkItem("fixture2", "页面布局与交互", "WEB应用系统实践", "103:203", "未提交", "剩余5小时", now + 18_000_000,
                now, false, "https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/task?courseId=103&classId=203&workId=fixture2"),
            HomeworkItem("fixture3","固定日期的过期作业","线性代数","101:201","未提交","截止：2026-10-30 15:48",
                java.time.Instant.parse("2026-10-30T07:48:00Z").toEpochMilli(),now,false,"https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/task?courseId=101&classId=201&workId=fixture3")))
        val fixture = HomeworkPanel(activity, app, {}, object : HomeworkSource {
            override fun fetch(courses: List<Course>) = records
            override fun cancel() {}
        }, object : HomeworkCache {
            override fun read(identity: String) = records
            override fun write(identity: String, snapshot: HomeworkSnapshot) {}
        })
        try {
            val original = views(activity.window.decorView).filterIsInstance<HomeworkPanel>().single()
            val parent = original.parent as ViewGroup
            parent.removeView(original); parent.addView(fixture)
            button(activity, "作业").performClick(); layout(activity.window.decorView)
            assertTrue(views(fixture).filterIsInstance<TextView>().any { it.text.toString() == "页面布局与交互" })
            preview(activity.window.decorView, "作业-示例数据")
            views(fixture).filterIsInstance<Button>().first{it.text=="设置完成状态"}.performClick()
            val choices=ShadowAlertDialog.getLatestAlertDialog()
            layout(choices.window!!.decorView,358,368)
            preview(choices.window!!.decorView,"作业-完成状态菜单")
            choices.dismiss()
            val scroll=views(activity.window.decorView).filterIsInstance<ScrollView>().single{it.visibility==View.VISIBLE && it.contentDescription=="作业页面"}
            scroll.scrollTo(0,380);layout(activity.window.decorView)
            preview(activity.window.decorView,"作业-临期红卡")
            button(activity,"已过期").performClick();scroll.scrollTo(0,270);layout(activity.window.decorView)
            assertTrue(views(fixture).filterIsInstance<TextView>().any{it.text.contains("截止：2026-10-30 15:48")})
            preview(activity.window.decorView,"作业-固定日期已过期")
        } finally { fixture.close() }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-mdpi")
    fun narrowScreenWithLargeTextKeepsNavigationAndPrimaryActionReachable() {
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(app.resources.configuration.apply { fontScale = 1.5f }, app.resources.displayMetrics)
        val activity = main(); layout(activity.window.decorView, 320, 720)
        val start = button(activity, "开始通知监测")
        assertTrue(start.width > 0 && start.width <= 320)
        assertTrue(start.height >= 48)
        for (title in listOf("首页", "课程", "作业", "我的")) {
            val view = button(activity, title)
            val location = IntArray(2); view.getLocationOnScreen(location)
            assertTrue(location[0] >= 0 && location[0] + view.width <= 320)
            assertTrue(view.height >= 48)
        }
        preview(activity.window.decorView, "首页-窄屏大字号")
        button(activity,"我的").performClick();layout(activity.window.decorView,320,720)
        preview(activity.window.decorView,"我的-窄屏大字号")
        val tile=button(activity,"签到中心")
        assertTrue(tile.height>=48);assertTrue(tile.width in 48..320)
        assertNotNull(tile.findViewWithTag<View>("profile-sign-icon"))
        val profile=views(activity.window.decorView).filterIsInstance<ProfilePanel>().single()
        assertTrue(tile.bottom in (profile.height*0.4f).toInt()..(profile.height*0.65f).toInt())
        assertProfileTools(activity)
        val settings=button(activity,"提醒设置") as Button
        val tools=button(activity,"更多工具") as Button
        assertTrue(settings.left < tools.left && settings.top == tools.top)
        assertTrue((settings.compoundDrawablesRelative+tools.compoundDrawablesRelative).all{it==null})
        assertTrue(views(profile).filterIsInstance<Button>().none{it.text in
            setOf("运行日志与故障反馈","提醒记录","测试与诊断","使用指南","更新日志")})
        tools.performClick()
        layout(activity.window.decorView,320,720)
        val toolsPage=views(activity.window.decorView).single{it.contentDescription=="更多工具页面"}
        assertEquals(View.VISIBLE,toolsPage.visibility)
        assertEquals(listOf("运行日志与故障反馈","提醒记录","测试与诊断"),
            views(toolsPage).filterIsInstance<Button>().map{it.text.toString()})
        button(activity,"测试与诊断").performClick()
        activity.onBackPressed();assertEquals("tools",app.prefs.getString("ui.route",""))
        activity.onBackPressed();assertEquals("profile",app.prefs.getString("ui.route",""))
        button(activity,"☰ 使用帮助").performClick()
        val sheet=ShadowAlertDialog.getLatestAlertDialog()
        val root=sheet.window!!.decorView;layout(root,288,600)
        assertTrue(views(root).any{it is ScrollView})
        assertTrue(views(root).single{it.contentDescription=="使用指南"}.width<=288)
        preview(root,"使用帮助-窄屏大字号");sheet.dismiss()
        button(activity,"签到中心").performClick()
        for(section in listOf("进行中","已结束","设置","记录")){
            val tab=button(activity,section);tab.performClick();layout(activity.window.decorView,320,720)
            assertTrue(tab.isSelected);val xy=IntArray(2);tab.getLocationOnScreen(xy)
            assertTrue(xy[0]>=0 && xy[0]+tab.width<=320);assertTrue(tab.height>=48)
            assertEquals(1,(tab as Button).lineCount)
            if(section=="设置")assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.CheckBox>().single{it.text=="拍照签到自动提交预选照片"}.width>=200)
            preview(activity.window.decorView,"签到中心-$section-窄屏大字号")
        }
    }

    private fun assertProfileTools(activity: MainActivity) {
        val profile=views(activity.window.decorView).filterIsInstance<ProfilePanel>().single()
        val tiles=listOf("提醒课程","扫码签到","关联账号").map { title ->
            views(profile).single { it.contentDescription==title }
        }
        assertTrue(tiles.maxOf { it.width }-tiles.minOf { it.width }<=1)
        assertTrue(tiles.all { it.width>=48 && it.height==tiles.first().height && it.height>=48 })
        assertTrue(tiles.zipWithNext().all { (left,right) -> left.right<right.left })
        val settings=button(activity,"提醒设置")
        val credit=views(activity.window.decorView).filterIsInstance<TextView>()
            .single{it.text=="Class Bell ${BuildConfig.VERSION_NAME}\n作者：Forest5326" && it.parent is ProfilePanel}
        val shortcuts=(tiles.first().parent as View)
        val links=(settings.parent as View)
        assertTrue(shortcuts.bottom<links.top && links.bottom<=credit.top && credit.height>=32)
    }
}
