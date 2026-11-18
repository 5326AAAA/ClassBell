package cn.classbell.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

/** Verifies OS API wiring and recovery. This suite cannot establish OEM lockscreen delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LockScreenAlarmTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val controllers = mutableListOf<ServiceController<MonitorService>>()
    @Before fun setup() {
        app.prefs.edit().clear().putBoolean("voiceEnabled", false).putBoolean("ringEnabled", false).commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(manager).setNotificationListenerAccessGranted(ComponentName(app, XxtNotificationListener::class.java), true)
    }
    @After fun cleanup() {
        controllers.reversed().forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun service() = Robolectric.buildService(MonitorService::class.java).create().also { controllers.add(it) }.get()
    private fun send(service: MonitorService, action: String) {
        service.onStartCommand(Intent(app, MonitorService::class.java).setAction(action), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun tenSecondTestUsesSystemAlarmClockAndDoesNotPassTheListenerStage() {
        val service = service()
        val before = System.currentTimeMillis()
        send(service, MonitorService.ALARM_TEST)
        val next = alarms.nextAlarmClock
        assertNotNull(next)
        assertTrue(next.triggerTime in (before + 10_000)..(System.currentTimeMillis() + 10_000))
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        val progress = app.simulation.snapshot()!!
        assertTrue(progress.alarmOnly); assertFalse(progress.received)
        // Model OS dispatch explicitly, not a Handler tick masquerading as an alarm.
        TestAlarmReceiver().onReceive(app, Intent().putExtra(AlarmScheduler.EXTRA_NONCE, progress.nonce))
        val request = shadowOf(app).nextStartedService
        assertEquals(MonitorService.FIRE_TEST, request.action)
        service.onStartCommand(request, 0, 2)
        assertTrue(app.simulation.snapshot()!!.alerted)
        assertFalse(app.simulation.snapshot()!!.received)
        val notification = shadowOf(manager).getNotification(UrgentAlert.ID)!!
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
        assertNotNull(notification.fullScreenIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(app.simulation.snapshot()!!.finished)
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Test fun deniedExactAlarmPermissionDoesNotPretendToScheduleATimer() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        send(service(), MonitorService.ALARM_TEST)
        assertNull(alarms.nextAlarmClock)
        assertTrue(app.simulation.snapshot()!!.finished)
        assertTrue(app.simulation.snapshot()!!.message.contains("闹钟和提醒"))
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Test fun simulationGrantSurvivesApplicationProcessRecreation() {
        val nonce = app.simulation.begin(SystemClock.elapsedRealtime(), 10_000, alarmOnly = true)
        val restored = SimulationSession(app.prefs)
        assertTrue(restored.active(nonce, SystemClock.elapsedRealtime() + 10_000))
        assertTrue(restored.snapshot()!!.alarmOnly)
        restored.finish("已取消")
        assertFalse(SimulationSession(app.prefs).active(nonce, SystemClock.elapsedRealtime()))
    }
    @Test fun staleOrCancelledAlarmCannotStartAService() {
        val nonce = app.simulation.begin(SystemClock.elapsedRealtime(), 10_000)
        AlarmScheduler.schedule(app, nonce, 10_000)
        TestAlarmReceiver().onReceive(app, Intent().putExtra(AlarmScheduler.EXTRA_NONCE, "incorrect"))
        assertNull(shadowOf(app).nextStartedService)
        AlarmScheduler.cancel(app)
        TestAlarmReceiver().onReceive(app, Intent().putExtra(AlarmScheduler.EXTRA_NONCE, nonce))
        assertNull(shadowOf(app).nextStartedService)
        assertNull(alarms.nextAlarmClock)
    }
    @Test fun duplicateAlarmBroadcastIsConsumedOnlyOnce() {
        val nonce = app.simulation.begin(SystemClock.elapsedRealtime(), 10_000)
        AlarmScheduler.schedule(app, nonce, 10_000)
        val intent = Intent().putExtra(AlarmScheduler.EXTRA_NONCE, nonce)
        TestAlarmReceiver().onReceive(app, intent)
        assertNotNull(shadowOf(app).nextStartedService)
        TestAlarmReceiver().onReceive(app, intent)
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun stickyRecoveryRestoresAnUnacknowledgedRealAlert() {
        val old = service()
        send(old, MonitorService.START)
        val signal = SignSignal("course-qr", "课堂签到", "扫码签到", System.currentTimeMillis(), System.currentTimeMillis())
        MonitorService.deliver(app, signal)
        shadowOf(Looper.getMainLooper()).idle()
        controllers.removeAt(0).destroy()
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        val recovered = service()
        assertEquals(android.app.Service.START_STICKY, recovered.onStartCommand(null, 0, 2))
        assertTrue(app.prefs.getString("alert", "")!!.contains("扫码签到"))
        assertNotNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        MonitorService.acknowledge(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(signal.key in app.acknowledged())
        assertEquals("", app.prefs.getString("alert", ""))
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Suppress("DEPRECATION")
    @Test fun listenerDoesNotSilentlyDropANewEventAfterMonitorProcessLoss() {
        app.prefs.edit().putBoolean("monitorEnabled", true).putLong("monitorStartedAt", System.currentTimeMillis() - 1000).commit()
        manager.createNotificationChannel(android.app.NotificationChannel("source", "source", 3))
        val notification = Notification.Builder(app, "source").setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("课堂活动").setContentText("老师发起位置签到").build()
        val listener = Robolectric.buildService(XxtNotificationListener::class.java).create()
        listener.get().onNotificationPosted(StatusBarNotification(NotificationRules.SOURCE_PACKAGE, NotificationRules.SOURCE_PACKAGE,
            99, "course", Process.myUid(), Process.myPid(), 0, notification, Process.myUserHandle(), System.currentTimeMillis()))
        assertEquals(1, SignalInbox.read(app).size)
        assertEquals(MonitorService.SIGNAL, shadowOf(app).nextStartedService.action)
        assertNotNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        MonitorService.acknowledge(app)
        assertTrue(SignalInbox.read(app).isEmpty())
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        listener.destroy()
    }
    @Test fun stopActionRevokesMonitoringAndScheduledTest() {
        val service = service()
        send(service, MonitorService.START)
        send(service, MonitorService.ALARM_TEST)
        send(service, MonitorService.STOP)
        assertFalse(app.prefs.getBoolean("monitorEnabled", true))
        assertNull(alarms.nextAlarmClock)
    }
    @Test fun notificationStopActionRequiresTheCurrentAlertToken() {
        val signal = SignSignal("course-photo", "课堂活动", "拍照签到", System.currentTimeMillis(), System.currentTimeMillis())
        SignalInbox.enqueue(app, signal)
        UrgentAlert.publish(app)
        val token = app.prefs.getString("activeAlertToken", null)
        AlertActionReceiver().onReceive(app, Intent().putExtra(UrgentAlert.EXTRA_TOKEN, "stale"))
        assertNotNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        assertEquals(1, SignalInbox.read(app).size)
        AlertActionReceiver().onReceive(app, Intent().putExtra(UrgentAlert.EXTRA_TOKEN, token))
        assertTrue(SignalInbox.read(app).isEmpty())
        assertTrue(signal.key in app.acknowledged())
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Test fun dismissedAlertCannotReopenThroughAnOldFullScreenIntent() {
        UrgentAlert.publish(app)
        val intent = Intent(app, AlertActivity::class.java).putExtra(UrgentAlert.EXTRA_TOKEN, app.prefs.getString("activeAlertToken", null))
        UrgentAlert.clear(app)
        val controller = Robolectric.buildActivity(AlertActivity::class.java, intent).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }
}
