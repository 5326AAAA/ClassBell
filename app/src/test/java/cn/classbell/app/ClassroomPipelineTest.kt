package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClassroomPipelineTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var monitorController: ServiceController<MonitorService>
    private lateinit var listenerController: ServiceController<XxtNotificationListener>
    private val monitor get() = monitorController.get()
    private val listener get() = listenerController.get()
    @Before fun setup() {
        app.prefs.edit().clear().putBoolean("voiceEnabled", false).putBoolean("ringEnabled", false).commit()
        shadowOf(manager).setNotificationListenerAccessGranted(ComponentName(app, XxtNotificationListener::class.java), true)
        monitorController = Robolectric.buildService(MonitorService::class.java).create()
        listenerController = Robolectric.buildService(XxtNotificationListener::class.java).create()
        listener.onListenerConnected()
        manager.createNotificationChannel(NotificationChannel("test-source", "test-source", NotificationManager.IMPORTANCE_DEFAULT))
    }
    @After fun cleanup() {
        listenerController.destroy(); monitorController.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun start() { monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.START), 0, 1) }
    @Suppress("DEPRECATION")
    private fun post(text: String, title: String = "测试课程", notification: Notification? = null,
                     pkg: String = NotificationRules.SOURCE_PACKAGE, tag: String = "course", id: Int = 99) {
        val n = notification ?: Notification.Builder(app, "test-source").setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title).setContentText(text).build()
        listener.onNotificationPosted(StatusBarNotification(pkg, pkg, id, tag, Process.myUid(), Process.myPid(), 0,
            n, Process.myUserHandle(), System.currentTimeMillis()))
    }
    @Test fun localClassroomSimulationRequiresTheListenerAndPreservesItsVoiceKind() {
        monitor.onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.SIMULATE)
            .putExtra(MonitorService.EXTRA_SIMULATION_KIND, ReminderKind.CLASSROOM.name), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.simulation.snapshot()!!.alerted)
        val source = shadowOf(manager).getNotification(SimulationNotification.TAG, SimulationNotification.ID)!!
        post("", notification = source, pkg = app.packageName, tag = SimulationNotification.TAG, id = SimulationNotification.ID)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.simulation.snapshot()!!.alerted)
        assertEquals(ReminderKind.CLASSROOM, app.simulation.snapshot()!!.kind)
        assertEquals(setOf(ReminderKind.CLASSROOM.name), app.prefs.getStringSet("activeAlertKinds", emptySet()))
        assertEquals("课堂活动提醒", shadowOf(manager).getNotification(UrgentAlert.ID).extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0L, app.prefs.getLong("lastCheck", 0))
    }
    @Test fun disablingClassroomRemindersDoesNotDisableSigns() {
        app.prefs.edit().putBoolean("classroomEnabled", false).commit()
        start()
        post("老师发起随堂练习")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", app.prefs.getString("alert", ""))
        post("老师发起签到")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.prefs.getString("alert", "")!!.contains("签到"))
        assertEquals(setOf(ReminderKind.SIGN_IN.name), app.prefs.getStringSet("activeAlertKinds", emptySet()))
    }
    @Test fun topicOnlyNotificationWithoutAccountCannotInventAnActivityAlert() {
        start()
        post("主合取范式", "离散数学")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", app.prefs.getString("alert", ""))
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        assertEquals(0L, app.prefs.getLong("lastCheck", 0))
    }
    @Test fun topicOnlyNotificationQueriesAllSelectedCoursesAndKeepsBothKinds() {
        app.prefs.edit().putBoolean("loggedIn", true).putString("courses",
            """[{"id":"101","classId":"201","name":"测试离散数学"},{"id":"102","classId":"202","name":"测试数据结构"}]""")
            .putStringSet("selected", setOf("101:201", "102:202")).commit()
        val queried = ConcurrentLinkedQueue<String>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.queryParameter("courseId") ?: error("Unexpected request")
            queried.add(id)
            val type = if (id == "101") 42 else 2
            val title = if (type == 42) "主合取范式" else "扫码签到"
            val body = """{"result":1,"data":{"activeList":[{"id":"a-$id","activeType":$type,"status":1,"nameOne":"$title"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        // An in-process terminal interceptor: no network, cookies or real account is used.
        ChaoxingApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(app.api, transport)
        start()
        post("主合取范式", "离散数学")
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (app.prefs.getLong("lastCheck", 0) == 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Supplementary query did not finish", app.prefs.getLong("lastCheck", 0) > 0)
        assertEquals(listOf("101", "102"), queried.toList())
        val alert = app.prefs.getString("alert", "")!!
        assertTrue(alert.contains("主合取范式")); assertTrue(alert.contains("扫码签到"))
        assertEquals(setOf(ReminderKind.SIGN_IN.name, ReminderKind.CLASSROOM.name), app.prefs.getStringSet("activeAlertKinds", emptySet()))
    }
    @Test fun twoPhotoRequiredSignSamplesBothReachTheSignAlertAndAcknowledgeTogether() {
        app.prefs.edit().putBoolean("loggedIn", true).putString("courses",
            """[{"id":"101","classId":"201","name":"测试WEB实践"},{"id":"102","classId":"202","name":"测试数据结构"}]""")
            .putStringSet("selected", setOf("101:201", "102:202")).commit()
        val queried = ConcurrentLinkedQueue<String>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val courseId = chain.request().url.queryParameter("courseId") ?: error("Unexpected request")
            queried.add(courseId)
            val activityId = if (courseId == "101") 5000000001001L else 5000000001002L
            val body = """{"result":1,"data":{"activeList":[{"id":$activityId,"activeType":2,"type":2,
                "otherId":"0","source":15,"status":1,"userStatus":0,"nameOne":"普通签到"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        ChaoxingApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(app.api, transport)
        start()
        // Synthetic notification and in-process responses: this does not send a real phone notification.
        post("有新的课程内容", "课程消息")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (app.prefs.getLong("lastCheck", 0) == 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.prefs.getLong("lastCheck", 0) > 0)
        assertEquals(listOf("101", "102"), queried.toList())
        val alert = app.prefs.getString("alert", "")!!
        assertTrue(alert.contains("测试WEB实践")); assertTrue(alert.contains("测试数据结构"))
        val saved = org.json.JSONObject(app.prefs.getString("pendingAlerts", "{}").orEmpty())
        assertEquals(2, saved.length())
        assertEquals(setOf(ReminderKind.SIGN_IN.name), app.prefs.getStringSet("activeAlertKinds", emptySet()))
        assertEquals("签到提醒", shadowOf(manager).getNotification(UrgentAlert.ID).extras.getString(Notification.EXTRA_TITLE))
        MonitorService.acknowledge(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", app.prefs.getString("alert", ""))
        assertEquals(2, app.acknowledged().size)
        assertNull(shadowOf(manager).getNotification(UrgentAlert.ID))
    }
    @Test fun classroomPendingStateKeepsItsKindAcrossServiceRecreation() {
        start(); post("随堂练习")
        shadowOf(Looper.getMainLooper()).idle()
        monitorController.destroy()
        monitorController = Robolectric.buildService(MonitorService::class.java).create()
        monitor.onStartCommand(null, 0, 2)
        assertEquals(setOf(ReminderKind.CLASSROOM.name), app.prefs.getStringSet("activeAlertKinds", emptySet()))
        assertTrue(app.prefs.getString("alert", "")!!.contains("随堂练习"))
    }
    @Test fun inboxPersistsKnownAndCheckOnlyKindsWithoutMixingThem() {
        val now = System.currentTimeMillis()
        SignalInbox.enqueue(app, SignSignal("one", "课程", "随堂练习", now, now, kind = ReminderKind.CLASSROOM))
        SignalInbox.enqueue(app, SignSignal("two", "课程", "题目名称", now, now, kind = null))
        assertEquals(listOf(ReminderKind.CLASSROOM, null), SignalInbox.read(app).map { it.kind })
    }
    @Test fun cooldownHoldsABoundedHandoffLockAndAckCancelsIt() {
        app.prefs.edit().putBoolean("loggedIn", true)
            .putString("courses", """[{"id":"101","classId":"201","name":"测试离散数学"}]""")
            .putStringSet("selected", setOf("101:201")).commit()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"result":1,"data":{"activeList":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        ChaoxingApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(app.api, transport)
        start()
        MonitorService::class.java.getDeclaredField("lastEventQuery").apply { isAccessible = true }
            .setLong(monitor, android.os.SystemClock.elapsedRealtime())
        post("主合取范式", "离散数学")
        shadowOf(Looper.getMainLooper()).idle()
        val lock = MonitorService::class.java.getDeclaredField("dispatchLock").apply { isAccessible = true }
            .get(monitor) as android.os.PowerManager.WakeLock
        assertTrue(lock.isHeld)
        assertEquals(0, requests.get())
        MonitorService.acknowledge(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(lock.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertEquals(0, requests.get())
    }
}
