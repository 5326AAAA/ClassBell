package cn.classbell.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StopMonitoringTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<MonitorService>
    private val service get() = controller.get()
    @Before fun setup() {
        app.prefs.edit().clear().putBoolean("voiceEnabled", false).putBoolean("ringEnabled", false).commit()
        shadowOf(manager).setNotificationListenerAccessGranted(ComponentName(app, XxtNotificationListener::class.java), true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller = Robolectric.buildService(MonitorService::class.java).create()
        service.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    private fun status(): Notification? = shadowOf(manager).getNotification(7)
    private fun alert() {
        val now = System.currentTimeMillis()
        MonitorService.deliver(app, SignSignal("new", "测试课程", "请签到", now, now))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun idleMonitorDoesNotOfferAStopVibrationAction() {
        assertEquals(listOf("停止监测"), status()!!.actions.map { it.title.toString() })
    }
    @Test fun mainStopButtonImmediatelyRemovesBothNotificationsBeforeServiceDestruction() {
        alert()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        views(activity.get().window.decorView).filterIsInstance<Button>().single { it.text == "停止监测" }.performClick()
        assertFalse(MonitorService.running)
        assertFalse(app.prefs.getBoolean("monitorEnabled", true))
        assertNull(status()); assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        assertEquals("监测已停止", app.prefs.getString("status", ""))
        assertNull(app.getSystemService(JobScheduler::class.java).getPendingJob(MonitorRecovery.JOB_ID))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertNull(status())
        activity.pause().stop().destroy()
    }
    @Test fun acknowledgementKeepsMonitoringButRemovesOnlyTheActiveReminder() {
        alert()
        assertEquals(listOf("停止声音和振动", "停止监测"), status()!!.actions.map { it.title.toString() })
        status()!!.actions.first().actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(MonitorService.running)
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        assertEquals(listOf("停止监测"), status()!!.actions.map { it.title.toString() })
    }
    @Test fun ignoringOneCourseActivityStopsItsAlertAndKeepsOtherActivitiesEligible() {
        val owner=accountKey("test-owner")
        app.prefs.edit().putString("identity",owner).commit()
        val course=Course("101","201","合成课程")
        val first=SignActivity("301","第一条签到",course)
        val second=SignActivity("302","第二条签到",course)
        val countsClass=MonitorService::class.java.declaredClasses.single{it.simpleName=="ActivityReminderCounts"}
        val counts=countsClass.getDeclaredConstructor().apply{isAccessible=true}.newInstance()
        val accept=MonitorService::class.java.getDeclaredMethod("acceptActivities",List::class.java,countsClass)
            .apply{isAccessible=true}
        fun poll(){accept.invoke(service,listOf(first,second),counts)}
        fun currentAlert()=app.prefs.getString("alert","").orEmpty()
        poll()
        assertTrue(currentAlert().contains(first.title))
        app.setSignDetectionIgnored(owner,first,true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(currentAlert().contains(first.title))
        assertTrue(currentAlert().contains(second.title))
        poll()
        assertFalse(currentAlert().contains(first.title))
        app.setSignDetectionIgnored(owner,first,false)
        poll()
        assertTrue(currentAlert().contains(first.title))
    }
    @Test fun queuedAcknowledgementAndLateCommandsCannotRepostAfterStop() {
        alert()
        val staleAck = status()!!.actions.first().actionIntent
        MonitorService.acknowledge(app) // deliberately leave the ACK callback queued
        MonitorService.stopMonitoring(app)
        staleAck.send()
        service.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.ACK), 0, 3)
        MonitorService.listenerChanged()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(status()); assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        assertFalse(MonitorService.running)
    }
    @Test fun notificationStopUsesBroadcastWithoutRestartingAService() {
        val stop = status()!!.actions.last().actionIntent
        stop.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MonitorService.running)
        assertNull(status())
        assertNull(shadowOf(app).nextStartedService)
        stop.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(status()); assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun fullStopCancelsDelayedSimulationAndReleasesAllWakeLocks() {
        service.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.SIMULATE)
            .putExtra(MonitorService.EXTRA_SIMULATION_DELAY, 10_000L), 0, 2)
        assertNotNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
        MonitorService.stopMonitoring(app)
        assertNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
        assertTrue(app.simulation.snapshot()!!.finished)
        listOf("queryLock", "dispatchLock", "simulationLock").forEach { name ->
            val lock = MonitorService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as PowerManager.WakeLock
            assertFalse(name, lock.isHeld)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertNull(status()); assertNull(shadowOf(manager).getNotification(SimulationNotification.TAG, SimulationNotification.ID))
    }
    @Test fun receiverCleanupFailureCannotAbortOneTapStopOrReviveAWaitingNotification() {
        var attempts=0
        val receiver=object:ActivityReceiver {
            override fun start() {}
            override fun stop() {attempts++;throw android.os.NetworkOnMainThreadException()}
        }
        MonitorService::class.java.getDeclaredField("backend").apply{isAccessible=true}.set(service,receiver)
        app.prefs.edit().putBoolean("backendPush",true).putBoolean("autoNormal.fixture",true).commit()
        alert()
        MonitorService.stopMonitoring(app)
        MonitorService.stopMonitoring(app)
        assertFalse(MonitorService.running)
        assertFalse(app.prefs.getBoolean("monitorEnabled",true))
        assertEquals(1,attempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertNull(status());assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Test fun stoppingReceiverBeforeAuthenticationIsIdempotentAndDoesNotStartNetworking() {
        val receiver=BackendImClient(app){fail("Stopped receiver delivered an activity")}
        receiver.stop();receiver.stop()
        assertFalse(app.prefs.getBoolean("backend.connected",false))
    }
}
