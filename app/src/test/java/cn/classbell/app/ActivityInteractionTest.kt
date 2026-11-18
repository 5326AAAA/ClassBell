package cn.classbell.app

import android.app.AlertDialog
import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class ActivityInteractionTest {
    @Test fun classroomSimulationButtonDispatchesTheCorrectKind() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, XxtNotificationListener::class.java), true)
        val activity = main()
        button(activity, "立即模拟课堂活动").performClick()
        val request = shadowOf(activity).nextStartedService
        assertEquals(MonitorService.SIMULATE, request.action)
        assertEquals(ReminderKind.CLASSROOM.name, request.getStringExtra(MonitorService.EXTRA_SIMULATION_KIND))
    }
    @Test fun classroomVoiceTestHasAnActualServiceAction() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = main()
        button(activity, "测试课堂活动语音 · 5 秒").performClick()
        assertEquals(MonitorService.TEST_CLASSROOM, shadowOf(activity).nextStartedService.action)
    }
    private val controllers = mutableListOf<ActivityController<out android.app.Activity>>()
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    @Before fun cleanState() {
        setMonitoring(false)
        app.prefs.edit().clear().commit()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        CookieManager.getInstance().removeAllCookies(null)
    }
    @After fun cleanup() {
        setMonitoring(false)
        controllers.reversed().forEach { it.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun setMonitoring(value: Boolean) {
        MonitorService::class.java.getDeclaredField("running").apply { isAccessible = true }.setBoolean(null, value)
    }
    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controllers.add(it) }.get()
    private fun web(): WebLoginActivity = Robolectric.buildActivity(WebLoginActivity::class.java).setup().also { controllers.add(it) }.get()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(activity: android.app.Activity, text: String): Button = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == text }
    private fun dialogTitle(): String = views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView)
        .filterIsInstance<TextView>().joinToString(" ") { it.text }

    @Test fun monitoringDoesNotDisableAccountWebOrCourseButtons() {
        setMonitoring(true)
        val activity = main()
        listOf("登录并读取课程", "使用官方网页登录", "选择课程", "刷新课程列表", "退出当前账号").forEach {
            assertTrue("Button disabled while monitoring: $it", button(activity, it).isEnabled)
        }
    }
    @Test fun webButtonWhileMonitoringActuallyDispatchesWebLoginActivity() {
        setMonitoring(true)
        val activity = main()
        assertTrue(button(activity, "使用官方网页登录").performClick())
        assertEquals(WebLoginActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }
    @Test fun webLoginRemainsAnEscapeFromAnInProgressOperation() {
        val activity = main()
        MainActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(activity, true)
        MainActivity::class.java.getDeclaredMethod("renderState").apply { isAccessible = true }.invoke(activity)
        assertTrue(button(activity, "使用官方网页登录").isEnabled)
        button(activity, "使用官方网页登录").performClick()
        assertEquals(WebLoginActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }
    @Test fun inProgressOperationHasAWorkingCancelButton() {
        val activity = main()
        MainActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(activity, true)
        MainActivity::class.java.getDeclaredMethod("renderState").apply { isAccessible = true }.invoke(activity)
        val cancel = button(activity, "取消当前登录 / 查询")
        assertEquals(View.VISIBLE, cancel.visibility)
        cancel.performClick()
        assertEquals(View.GONE, cancel.visibility)
        assertTrue(button(activity, "登录并读取课程").isEnabled)
    }
    @Test fun emptyLoginHasVisibleFeedbackInsteadOfSilentlyDoingNothing() {
        setMonitoring(true)
        val activity = main()
        button(activity, "登录并读取课程").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertTrue(dialogTitle().contains("还没有填写完整"))
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("请先填写学习通账号和密码") })
    }
    @Test fun selectingCoursesBeforeLoginExplainsWhatIsMissing() {
        val activity = main()
        button(activity, "选择课程").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertTrue(dialogTitle().contains("还没有课程"))
    }
    @Test fun selectingExistingCoursesStillOpensWhileMonitoring() {
        app.prefs.edit().putString("courses", """[{"id":"101","classId":"201","name":"测试课程"}]""")
            .putBoolean("loggedIn", true).commit()
        setMonitoring(true)
        val activity = main()
        button(activity, "选择课程").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertTrue(dialogTitle().contains("选择需要监测的课程"))
    }
    @Test fun runningMonitorButtonExplainsThatItIsWaitingForNotifications() {
        setMonitoring(true)
        val activity = main()
        button(activity, "监测中 · 点此查看状态").performClick()
        assertTrue(dialogTitle().contains("监测正在运行"))
    }
    @Test fun voiceSwitchIsUsableDuringMonitoring() {
        setMonitoring(true)
        val activity = main()
        val box = views(activity.window.decorView).filterIsInstance<CheckBox>().single { it.text.contains("每 5 秒") }
        assertTrue(box.isChecked)
        box.performClick()
        assertFalse(app.prefs.getBoolean("voiceEnabled", true))
    }
    @Test fun mainAndOfficialWebLoginBothPermitScreenshots() {
        assertEquals(0, main().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(0, web().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
    @Test fun officialLoginPageLoadsImmediatelyWithoutWaitingForCookieRemoval() {
        val activity = web()
        val browser = views(activity.window.decorView).filterIsInstance<WebView>().single()
        assertEquals(ChaoxingProtocol.LOGIN_PAGE, shadowOf(browser).lastLoadedUrl)
        assertTrue(browser.settings.javaScriptEnabled)
        assertTrue(browser.settings.domStorageEnabled)
    }
    @Test fun verifyingBeforeWebLoginExplainsMissingSession() {
        val activity = web()
        button(activity, "我已完成网页登录 · 验证并读取课程").performClick()
        assertTrue(dialogTitle().contains("请先完成网页登录"))
    }
    @Test fun reloadButtonActuallyReloadsOfficialLoginPage() {
        val activity = web()
        val browser = views(activity.window.decorView).filterIsInstance<WebView>().single()
        browser.loadUrl("about:blank")
        button(activity, "重新加载登录页").performClick()
        assertEquals(ChaoxingProtocol.LOGIN_PAGE, shadowOf(browser).lastLoadedUrl)
    }
    @Test fun localSimulationDoesNotRequireAnAccountOrCourse() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, XxtNotificationListener::class.java), true)
        val activity = main()
        button(activity, "立即模拟发布签到").performClick()
        val request = shadowOf(activity).nextStartedService
        assertEquals(MonitorService.SIMULATE, request.action)
        assertEquals(0L, request.getLongExtra(MonitorService.EXTRA_SIMULATION_DELAY, -1))
        assertFalse(app.prefs.getBoolean("loggedIn", false))
    }
    @Test fun delayedSimulationButtonDispatchesTenSecondDelay() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, XxtNotificationListener::class.java), true)
        val activity = main()
        button(activity, "10 秒后模拟签到 · 可锁屏").performClick()
        assertEquals(10_000L, shadowOf(activity).nextStartedService.getLongExtra(MonitorService.EXTRA_SIMULATION_DELAY, -1))
    }
    @Test fun missingExactAlarmPermissionExplainsWhyCountdownDidNotStart() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val activity = main()
        button(activity, "10 秒后锁屏闹铃测试（不依赖学习通）").performClick()
        assertTrue(dialogTitle().contains("需要闹钟和提醒权限"))
        assertNull(shadowOf(activity).nextStartedService)
    }
}
