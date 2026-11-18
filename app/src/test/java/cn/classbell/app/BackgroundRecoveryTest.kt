package cn.classbell.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val jobs get() = app.getSystemService(JobScheduler::class.java)
    private val services = mutableListOf<ServiceController<MonitorService>>()
    private val listeners = mutableListOf<ServiceController<XxtNotificationListener>>()
    private val activities = mutableListOf<ActivityController<MainActivity>>()
    private var rebinds = 0

    @Before fun setup() {
        app.prefs.edit().clear().putBoolean("voiceEnabled", false).putBoolean("ringEnabled", false).commit()
        ListenerRecovery.connected()
        ListenerRecovery.requestBinding = { rebinds++; assertEquals(ComponentName(app, XxtNotificationListener::class.java), it) }
        XxtNotificationListener::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(null, false)
        access(true)
    }
    @After fun cleanup() {
        app.prefs.edit().putBoolean("monitorEnabled", false).commit()
        activities.reversed().forEach { it.pause().stop().destroy() }
        listeners.reversed().forEach { it.destroy() }
        services.reversed().forEach { it.destroy() }
        MonitorRecovery.cancel(app)
        ListenerRecovery.connected()
        ListenerRecovery.requestBinding = { NotificationListenerService.requestRebind(it) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun access(granted: Boolean) = shadowOf(manager).setNotificationListenerAccessGranted(
        ComponentName(app, XxtNotificationListener::class.java), granted)
    private fun listener() = Robolectric.buildService(XxtNotificationListener::class.java).create().also { listeners.add(it) }.get()
    private fun service() = Robolectric.buildService(MonitorService::class.java).create().also { services.add(it) }.get()
    private fun activity() = Robolectric.buildActivity(MainActivity::class.java).setup().also { activities.add(it) }.get()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun click(activity: MainActivity, label: String) = views(activity.window.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == label }.performClick()
    private fun start() = service().also {
        it.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun disconnectRequestsRebindWithoutRevokingExistingPermission() {
        val listener = listener()
        listener.onListenerConnected()
        listener.onListenerDisconnected()
        assertEquals(1, rebinds)
        assertTrue(XxtNotificationListener.hasAccess(app))
        assertFalse(XxtNotificationListener.connected)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun revokedPermissionNeverRequestsAnUnauthorizedRebind() {
        access(false)
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        listener().onListenerDisconnected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertEquals(0, rebinds)
    }
    @Test fun failedConnectionRetriesAndSuccessfulConnectionCancelsRetries() {
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        val listener = listener()
        listener.onListenerDisconnected()
        repeat(10) { ListenerRecovery.ensure(app) }
        assertEquals(1, rebinds)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, rebinds)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertEquals(2, rebinds)
    }
    @Test fun openingAppRestoresPreviouslyEnabledMonitorWithoutPermissionSettings() {
        app.prefs.edit().putBoolean("monitorEnabled", true).putLong("monitorStartedAt", 123L).commit()
        val activity = activity()
        assertEquals(MonitorRecovery.RESTORE, shadowOf(activity).nextStartedService.action)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(XxtNotificationListener.hasAccess(app))
    }
    @Test fun openingAppNeverEnablesAMonitorThatUserStopped() {
        val activity = activity()
        assertNull(shadowOf(activity).nextStartedService)
        assertNull(jobs.getPendingJob(MonitorRecovery.JOB_ID))
        assertFalse(app.prefs.getBoolean("monitorEnabled", false))
    }
    @Test fun restorePreservesOriginalMonitoringStartAndPendingAlert() {
        app.prefs.edit().putBoolean("monitorEnabled", true).putLong("monitorStartedAt", 123L)
            .putString("pendingAlerts", """{"event":{"text":"已有课堂活动","kind":"CLASSROOM"}}""").commit()
        val monitor = service()
        assertEquals(Service.START_STICKY, monitor.onStartCommand(Intent(app, MonitorService::class.java)
            .setAction(MonitorRecovery.RESTORE), 0, 1))
        assertEquals(123L, app.prefs.getLong("monitorStartedAt", 0))
        assertTrue(app.prefs.getString("alert", "")!!.contains("已有课堂活动"))
    }
    @Test fun monitoringSchedulesPersistedInexactRecoveryWithoutAlarmOrIdleWakeLock() {
        val monitor = start()
        val job = jobs.getPendingJob(MonitorRecovery.JOB_ID)!!
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(15 * 60_000L, job.intervalMillis)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        listOf("queryLock", "dispatchLock", "simulationLock").forEach {
            val lock = MonitorService::class.java.getDeclaredField(it).apply { isAccessible = true }.get(monitor) as PowerManager.WakeLock
            assertFalse(lock.isHeld)
        }
    }
    @Test fun serviceRecreationAndSystemJobRetainUserMonitoringChoice() {
        start()
        services.removeAt(0).destroy()
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertNotNull(jobs.getPendingJob(MonitorRecovery.JOB_ID))
        val job = Robolectric.buildService(MonitorRecoveryJob::class.java).create()
        assertFalse(job.get().onStartJob(null))
        assertEquals(MonitorRecovery.RESTORE, shadowOf(app).nextStartedService.action)
        job.destroy()
    }
    @Test fun explicitStopCancelsRecoveryAndDoesNotRestartAfterListenerReconnect() {
        val monitor = start()
        monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.STOP), 0, 2)
        assertFalse(app.prefs.getBoolean("monitorEnabled", true))
        assertNull(jobs.getPendingJob(MonitorRecovery.JOB_ID))
        listener().onListenerConnected()
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun mainStopButtonCancelsRecoveryEvenIfServiceAlreadyDied() {
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        MonitorRecovery.schedule(app)
        val activity = activity()
        while (shadowOf(activity).nextStartedService != null) { /* consume resume request */ }
        click(activity, "停止监测")
        assertFalse(app.prefs.getBoolean("monitorEnabled", true))
        assertNull(jobs.getPendingJob(MonitorRecovery.JOB_ID))
        MonitorRecoveryReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun bootAndUpdateRestoreOnlyEnabledMonitoring() {
        val receiver = MonitorRecoveryReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach {
            receiver.onReceive(app, Intent(it))
            assertEquals(MonitorRecovery.RESTORE, shadowOf(app).nextStartedService.action)
        }
    }
    @Test fun repairButtonRebindsAuthorizedListenerWithoutOpeningPermissionSettings() {
        val activity = activity()
        val initial = rebinds
        click(activity, "修复通知连接")
        assertTrue(rebinds >= initial)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(XxtNotificationListener.hasAccess(app))
    }
    @Test fun backgroundStartRejectionShowsRecoverableNotificationAndKeepsIntent() {
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        val restricted = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? { throw IllegalStateException("Background restricted") }
        }
        MonitorRecovery.restore(restricted, "测试回收恢复")
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertTrue(app.prefs.getString("status", "")!!.contains("打开 App"))
        val notice = shadowOf(manager).getNotification(206)!!
        assertNotNull(notice.contentIntent)
        assertTrue(XxtNotificationListener.hasAccess(app))
    }
    @Test fun oneHourVirtualIdleKeepsMonitoringEnabledAndDoesNotStartNetworkQueries() {
        start()
        listener().onListenerConnected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(75))
        assertTrue(MonitorService.running)
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertEquals(0L, app.prefs.getLong("lastCheck", 0))
    }
}
