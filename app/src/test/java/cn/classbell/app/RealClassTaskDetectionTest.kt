package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
class RealClassTaskDetectionTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    @Before fun reset() { app.prefs.edit().clear().commit() }
    @After fun cleanup() {
        app.prefs.edit().putBoolean("monitorEnabled", false).apply()
        MonitorRecovery.cancel(app); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun signal(text: String = "老师发布随堂练习", kind: ReminderKind? = ReminderKind.CLASSROOM,
                       nonce: String? = null, at: Long = System.currentTimeMillis()) =
        SignSignal("task-test", "测试课程", text, at, at, nonce, kind)

    @Test fun onlyFreshLearningClassroomSignalsCountAndSignReceiptStaysSeparate() {
        assertEquals(0L, RealClassTaskDetection.receivedAt(app))
        assertFalse(RealClassTaskDetection.record(app, app.packageName, signal(nonce = "local")))
        assertFalse(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(nonce = "local")))
        assertFalse(RealClassTaskDetection.record(app, "com.example.other", signal()))
        assertFalse(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("签到", ReminderKind.SIGN_IN)))
        assertFalse(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("题目名称", null)))
        assertFalse(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("课堂活动已结束")))
        app.prefs.edit().putLong("monitorStartedAt", 2000).commit()
        assertFalse(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(at = 1999)))
        assertTrue(RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal(at = 2000)))
        assertTrue(RealClassTaskDetection.description(app).contains("来源：学习通 App"))
        assertEquals(0L, RealSignDetection.receivedAt(app))
        assertFalse(app.prefs.all.values.any { it == "老师发布随堂练习" })
    }

    @Suppress("DEPRECATION")
    @Test fun listenerRecordsRealClassroomNotificationsWhenEnabledAndRejectsSummaryOrDisabled() {
        app.prefs.edit().putBoolean("monitorEnabled", true).putBoolean("verifyOnNotification", false).commit()
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("task-source", "source", 3))
        val controller = Robolectric.buildService(XxtNotificationListener::class.java).create()
        fun post(summary: Boolean = false) {
            val n = Notification.Builder(app, "task-source").setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("测试课程").setContentText("老师发布随堂练习").build()
            if (summary) n.flags = n.flags or Notification.FLAG_GROUP_SUMMARY
            controller.get().onNotificationPosted(StatusBarNotification(NotificationRules.SOURCE_PACKAGE,
                NotificationRules.SOURCE_PACKAGE, 19, "task", Process.myUid(), Process.myPid(), 0, n,
                Process.myUserHandle(), System.currentTimeMillis()))
        }
        post(summary = true)
        app.prefs.edit().putBoolean("classroomEnabled", false).commit(); post()
        assertEquals(0L, RealClassTaskDetection.receivedAt(app))
        app.prefs.edit().putBoolean("classroomEnabled", true).commit(); post()
        assertTrue(RealClassTaskDetection.receivedAt(app) > 0)
        app.prefs.edit().putBoolean("monitorEnabled", false).apply(); controller.destroy()
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    @Test fun receiptSurvivesReopeningAndClearLeavesSignRecordAndMonitoringAlone() {
        RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal())
        RealSignDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal("签到", ReminderKind.SIGN_IN))
        val signAt = RealSignDetection.receivedAt(app)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate()
        val root = controller.get().window.decorView
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text == "学习通真实课堂任务检测" })
        assertTrue(RealClassTaskDetection.receivedAt(app) > 0)
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        views(root).filterIsInstance<Button>().single { it.contentDescription == "清除真实课堂任务检测记录" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, RealClassTaskDetection.receivedAt(app))
        assertEquals(signAt, RealSignDetection.receivedAt(app))
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        app.prefs.edit().putBoolean("monitorEnabled", false).apply()
        controller.pause().stop().destroy()
        RealClassTaskDetection.record(app, NotificationRules.SOURCE_PACKAGE, signal())
        app.courseMemory.detach(app.prefs)
        assertEquals(0L, RealClassTaskDetection.receivedAt(app))
    }
}
