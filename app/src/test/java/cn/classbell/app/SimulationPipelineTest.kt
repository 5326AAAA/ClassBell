package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SimulationPipelineTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private lateinit var monitorController: ServiceController<MonitorService>
    private lateinit var listenerController: ServiceController<XxtNotificationListener>
    private lateinit var monitor: MonitorService
    private lateinit var listener: XxtNotificationListener
    private lateinit var manager: NotificationManager
    @Before fun setup() {
        app.prefs.edit().clear().putBoolean("voiceEnabled", false).putBoolean("ringEnabled", false).commit()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(ComponentName(app, XxtNotificationListener::class.java), true)
        monitorController = Robolectric.buildService(MonitorService::class.java).create()
        monitor = monitorController.get()
        listenerController = Robolectric.buildService(XxtNotificationListener::class.java).create()
        listener = listenerController.get()
        listener.onListenerConnected()
    }
    @After fun cleanup() {
        listenerController.destroy(); monitorController.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun simulate(delay: Long = 0): Notification? {
        monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.SIMULATE)
            .putExtra(MonitorService.EXTRA_SIMULATION_DELAY, delay), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(manager).getNotification(SimulationNotification.TAG, SimulationNotification.ID)
    }
    @Suppress("DEPRECATION")
    private fun event(notification: Notification, packageName: String = app.packageName,
                      tag: String? = SimulationNotification.TAG, id: Int = SimulationNotification.ID): StatusBarNotification =
        StatusBarNotification(packageName, packageName, id, tag, Process.myUid(), Process.myPid(), 0, notification,
            Process.myUserHandle(), System.currentTimeMillis())
    @Test fun generatedNotificationNeedsListenerCallbackBeforeItCanAlert() {
        val notification = simulate()!!
        assertTrue(app.simulation.snapshot()!!.sent); assertFalse(app.simulation.snapshot()!!.alerted)
        assertEquals("", app.prefs.getString("alert", ""))
        listener.onNotificationPosted(event(notification))
        shadowOf(Looper.getMainLooper()).idle()
        val progress = app.simulation.snapshot()!!
        assertTrue(progress.received); assertTrue(progress.recognized); assertTrue(progress.alerted)
        assertTrue(app.prefs.getString("alert", "")!!.contains("本地测试通知"))
        assertEquals(0L, app.prefs.getLong("lastCheck", 0))
    }
    @Test fun missingListenerCallbackReportsTheMissingStage() {
        simulate()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        val p = app.simulation.snapshot()!!
        assertTrue(p.sent); assertFalse(p.received); assertFalse(p.alerted); assertTrue(p.finished)
        assertTrue(p.message.contains("未收到监听回调"))
    }
    @Test fun cancellingDelayedTestPreventsNotificationPublication() {
        assertNull(simulate(10_000))
        monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.CANCEL_SIMULATION), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(shadowOf(manager).getNotification(SimulationNotification.TAG, SimulationNotification.ID))
        assertFalse(app.simulation.snapshot()!!.sent)
        assertTrue(shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
    @Test fun anotherAppCannotTurnACopiedPayloadIntoAnAlert() {
        listener.onNotificationPosted(event(simulate()!!, "com.example.unrelated"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.simulation.snapshot()!!.received); assertFalse(app.simulation.snapshot()!!.alerted)
    }
    @Test fun normalStatusNotificationsDoNotLoopBackIntoTheReminder() {
        listener.onNotificationPosted(event(simulate()!!, tag = null, id = 7))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.simulation.snapshot()!!.received)
    }
    @Test fun aWrongNonceCannotConsumeTheCurrentTestGrant() {
        val notification = simulate()!!
        val correctNonce = notification.extras.getString(SimulationNotification.EXTRA_NONCE)
        notification.extras.putString(SimulationNotification.EXTRA_NONCE, "not-current")
        listener.onNotificationPosted(event(notification))
        assertFalse(app.simulation.snapshot()!!.received)
        notification.extras.putString(SimulationNotification.EXTRA_NONCE, correctNonce)
        listener.onNotificationPosted(event(notification))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.simulation.snapshot()!!.alerted)
    }
    @Test fun localReminderAutomaticallyEndsAfterThirtySeconds() {
        listener.onNotificationPosted(event(simulate()!!))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(app.simulation.snapshot()!!.finished)
        assertEquals("", app.prefs.getString("alert", ""))
        assertNull(shadowOf(manager).getNotification(SimulationNotification.TAG, SimulationNotification.ID))
    }
    @Test fun genuineLearningSourceStillUsesTheContentRecognitionPath() {
        monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.START), 0, 1)
        manager.createNotificationChannel(NotificationChannel("sample", "sample", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = Notification.Builder(app, "sample").setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("测试课程").setContentText("老师发起了签到").build()
        listener.onNotificationPosted(event(notification, NotificationRules.SOURCE_PACKAGE, "course", 99))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.prefs.getString("alert", "")!!.contains("学习通通知"))
        assertNull(app.simulation.snapshot())
    }
}
