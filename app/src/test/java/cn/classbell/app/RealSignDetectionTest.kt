package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RealSignDetectionTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private fun signal(text: String = "请及时签到", kind: ReminderKind? = ReminderKind.SIGN_IN,
                       nonce: String? = null, postedAt: Long = System.currentTimeMillis()) =
        SignSignal("test-source", "测试课程", text, postedAt, postedAt, nonce, kind)
    @Before fun setup() { app.prefs.edit().clear().commit() }
    @After fun cleanup() {
        app.prefs.edit().putBoolean("monitorEnabled", false).apply()
        MonitorRecovery.cancel(app)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun initialStateDoesNotClaimARealNotificationWasDetected() {
        assertEquals(0L, RealSignDetection.receivedAt(app))
        assertTrue(RealSignDetection.description(app).contains("尚未检测到"))
    }
    @Test fun learningSignReceiptPersistsTimeAndBoundedTitleWithoutKeepingBody() {
        assertTrue(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal()))
        assertTrue(RealSignDetection.receivedAt(app) > 0)
        assertTrue(RealSignDetection.description(app).contains("来源：学习通 App"))
        assertTrue(RealSignDetection.description(app).contains("测试课程"))
        assertFalse(app.prefs.all.values.any { it == "请及时签到" })
    }
    @Test fun localSimulationAndUnrelatedAppsCannotMarkTheReceiptAsReal() {
        assertFalse(RealSignDetection.record(app, app.packageName, signal(nonce = "local-test")))
        assertFalse(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(nonce = "local-test")))
        assertFalse(RealSignDetection.record(app, "com.example.other", signal()))
        assertEquals(0L, RealSignDetection.receivedAt(app))
    }
    @Test fun classroomUnknownAndCompletedMessagesDoNotCountAsRealSignReceipts() {
        assertFalse(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("随堂练习", ReminderKind.CLASSROOM)))
        assertFalse(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("题目名称", null)))
        assertFalse(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("签到成功")))
        assertEquals(0L, RealSignDetection.receivedAt(app))
    }
    @Test fun notificationFromBeforeMonitoringCannotMarkCurrentReceipt() {
        app.prefs.edit().putLong("monitorStartedAt", 2000).commit()
        assertFalse(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(postedAt = 1999)))
        assertTrue(RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(postedAt = 2000)))
    }
    @Suppress("DEPRECATION")
    @Test fun actualListenerPathRecordsOnlyNonSummaryLearningSignCallbacks() {
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("source", "source", 3))
        val controller = Robolectric.buildService(XxtNotificationListener::class.java).create()
        fun post(pkg: String, summary: Boolean = false) {
            val n = Notification.Builder(app, "source").setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("测试课程").setContentText("老师发起拍照签到").build()
            if (summary) n.flags = n.flags or Notification.FLAG_GROUP_SUMMARY
            controller.get().onNotificationPosted(StatusBarNotification(pkg, pkg, 9, "course", Process.myUid(),
                Process.myPid(), 0, n, Process.myUserHandle(), System.currentTimeMillis()))
        }
        post(app.packageName); post(NotificationRules.SOURCE_PACKAGE, summary = true)
        assertEquals(0L, RealSignDetection.receivedAt(app))
        post(NotificationRules.SOURCE_PACKAGE)
        assertTrue(RealSignDetection.receivedAt(app) > 0)
        app.prefs.edit().putBoolean("monitorEnabled", false).apply()
        controller.destroy()
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    @Test fun mainPageShowsSavedReceiptAndClearDoesNotStopMonitoring() {
        RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal())
        val at = RealSignDetection.receivedAt(app)
        app.prefs.edit().putBoolean("monitorEnabled", true).apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>().any { it.text.contains("已检测到 · 通知来源") })
        assertEquals(at, RealSignDetection.receivedAt(app))
        views(controller.get().window.decorView).filterIsInstance<Button>().single { it.contentDescription == "清除真实签到检测记录" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, RealSignDetection.receivedAt(app))
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "尚未检测到真实学习通签到通知" })
        controller.pause().stop().destroy()
    }
}
