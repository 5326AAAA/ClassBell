package cn.classbell.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeworkPanelTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private val panels = mutableListOf<HomeworkPanel>()
    private val gates = mutableListOf<CountDownLatch>()
    private val memory = ConcurrentHashMap<String, HomeworkSnapshot>()
    private val cache = object : HomeworkCache {
        override fun read(identity: String) = memory[identity]
        override fun write(identity: String, snapshot: HomeworkSnapshot) { memory[identity] = snapshot }
    }
    private val course = Course("101", "201", "测试课程")
    private val owner get() = app.prefs.getString("identity", "")!!
    @Before fun setup() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
        app.courseMemory.activate(app.prefs, accountKey("homework-test"), listOf(course))
        activity = controller.setup().get()
    }
    @After fun cleanup() {
        gates.forEach { it.countDown() }
        panels.forEach { it.close() }
        controller.pause().stop().destroy()
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun fixture(title: String = "测试作业") = HomeworkSnapshot(System.currentTimeMillis(), listOf(
        HomeworkItem("1", title, course.name, course.key, "未提交", "剩余1小时", System.currentTimeMillis() + 3_600_000,
            System.currentTimeMillis(), false, "https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/task?courseId=101&classId=201&workId=1")))
    private fun source(fetch: () -> HomeworkSnapshot) = object : HomeworkSource {
        override fun fetch(courses: List<Course>) = fetch()
        override fun cancel() {}
    }
    private fun panel(source: HomeworkSource, login: () -> Unit = {}) = HomeworkPanel(activity, app, login, source, cache).also {
        panels.add(it); activity.setContentView(it)
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun texts(panel: View) = views(panel).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(panel: View, name: String) = views(panel).filterIsInstance<Button>().single { it.text.toString() == name }
    private fun eventually(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Async homework operation did not reach the expected state")
    }
    @Test fun manualRefreshPreservesMonitorAndRestoresAccountCache() {
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        val value = fixture()
        val panel = panel(source { value })
        button(panel, "一键更新作业").performClick()
        eventually { texts(panel).contains("测试作业") }
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertEquals(value, memory[owner])
        val restored = panel(source { throw IOException() })
        assertTrue(texts(restored).contains("测试作业"))
    }
    @Test fun failedRefreshKeepsLastSuccessfulRowsAndTimestamp() {
        val old = fixture("上次成功的作业"); memory[owner] = old
        val panel = panel(source { throw IOException() })
        panel.refresh()
        eventually { texts(panel).any { it.startsWith("更新未完成") } }
        assertTrue(texts(panel).contains("上次成功的作业"))
        assertEquals(old, memory[owner])
    }
    @Test fun noLoginDoesNotQueryAndProvidesLoginNavigation() {
        app.prefs.edit().putBoolean("loggedIn", false).commit()
        val count = AtomicInteger(); var login = 0
        val panel = panel(source { count.incrementAndGet(); fixture() }) { login++ }
        panel.refresh()
        assertEquals(0, count.get()); assertEquals(1, login)
    }
    @Test fun cancellationDiscardsEvenLateResultsFromSourceIgnoringInterrupt() {
        val started = CountDownLatch(1); val release = CountDownLatch(1).also { gates.add(it) }
        val value = fixture("不应出现的旧结果")
        val requests = AtomicInteger()
        val panel = panel(source {
            if (requests.getAndIncrement() == 0) {
                started.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                value
            } else fixture("新请求结果")
        })
        panel.refresh(); assertTrue(started.await(3, TimeUnit.SECONDS))
        button(panel, "取消作业更新").performClick(); panel.refresh(); release.countDown()
        eventually { texts(panel).contains("新请求结果") }
        assertFalse(texts(panel).contains("不应出现的旧结果")); assertEquals("新请求结果", memory[owner]!!.items.single().title)
    }
    @Test fun accountSwitchCannotShowOrCachePreviousAccountsLateResponse() {
        val original = owner
        val started = CountDownLatch(1); val release = CountDownLatch(1).also { gates.add(it) }
        val requests = AtomicInteger()
        val panel = panel(source {
            if (requests.getAndIncrement() == 0) {
                started.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                fixture("另一账号的旧响应")
            } else fixture("当前账号结果")
        })
        panel.refresh(); assertTrue(started.await(3, TimeUnit.SECONDS))
        app.courseMemory.activate(app.prefs, accountKey("another-account"), listOf(course))
        panel.syncSession(); panel.refresh(); release.countDown()
        eventually { texts(panel).contains("当前账号结果") }
        assertFalse(texts(panel).contains("另一账号的旧响应"))
        assertNull(memory[original]); assertEquals("当前账号结果", memory[owner]!!.items.single().title)
    }
    @Test fun stateFiltersSeparateSubmittedExpiredAndPeerReviews() {
        val item = fixture().items.single()
        memory[owner] = HomeworkSnapshot(System.currentTimeMillis(), listOf(item, item.copy(id="2", title="已交项", sourceStatus="待批阅"),
            item.copy(id="3", title="过期项", sourceStatus="未提交", dueAt=1L), item.copy(id="4", title="互评项", peerReview=true)))
        val panel = panel(source { fixture() })
        assertTrue(texts(panel).contains("测试作业")); assertFalse(texts(panel).contains("互评项"))
        for ((filter, title) in listOf("已完成" to "已交项", "已过期" to "过期项", "互评" to "互评项")) {
            button(panel, filter).performClick()
            assertTrue(texts(panel).contains(title)); assertFalse(texts(panel).contains("测试作业"))
        }
    }
    @Test fun refreshMovesWorkWithNewlyReadFixedDeadlineOutOfPendingAndShowsItsDate(){
        val item=fixture().items.single().copy(sourceTime="",dueAt=null)
        memory[owner]=HomeworkSnapshot(System.currentTimeMillis(),listOf(item))
        val due=HomeworkDeadline.read("开始时间：2026-10-26 14:38 截止时间：2026-10-30 15:48",System.currentTimeMillis())!!
        val panel=panel(source{HomeworkSnapshot(System.currentTimeMillis(),listOf(item.copy(dueAt=due.at,sourceTime=due.text)))})
        assertTrue(texts(panel).contains("测试作业"));panel.refresh()
        eventually{texts(panel).any{it=="未完成 0　已完成 0　已过期 1"}}
        assertFalse(texts(panel).contains("测试作业"));button(panel,"已过期").performClick()
        assertTrue(texts(panel).contains("测试作业"));assertTrue(texts(panel).any{it.contains("截止：2026-10-30 15:48")})
    }
    @Test fun expiredSessionKeepsCachedRowsAndDoesNotStopMonitor() {
        memory[owner] = fixture("缓存作业")
        app.prefs.edit().putBoolean("monitorEnabled", true).commit()
        val panel = panel(source { throw LoginRequired() })
        panel.refresh()
        eventually { !app.prefs.getBoolean("loggedIn", true) }
        assertTrue(texts(panel).contains("缓存作业")); assertTrue(app.prefs.getBoolean("monitorEnabled", false))
    }
    @Test fun linksAreExplicitToChaoxingAndFallBackToItsLauncherOnly() {
        val intents = mutableListOf<Intent>()
        val url = HomeworkParser.WEB_URL
        assertEquals(HomeworkOpenResult.DIRECT, HomeworkLinks.open(activity, url, { intents.add(it) }))
        assertEquals(NotificationRules.SOURCE_PACKAGE, intents.single().`package`)
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        intents.clear()
        val launcher = Intent(Intent.ACTION_MAIN).setPackage(NotificationRules.SOURCE_PACKAGE)
        val result = HomeworkLinks.open(activity, url, {
            if (it.action == Intent.ACTION_VIEW) throw ActivityNotFoundException()
            intents.add(it)
        }, { launcher })
        assertEquals(HomeworkOpenResult.APP_HOME, result); assertEquals(launcher, intents.single())
        assertEquals(HomeworkOpenResult.UNAVAILABLE, HomeworkLinks.open(activity, "javascript:alert(1)", { fail("Invalid URL launched") }))
        assertEquals(HomeworkOpenResult.UNAVAILABLE, HomeworkLinks.open(activity, url, { throw ActivityNotFoundException() }, { null }))
    }
    @Test fun manualCompletionMovesFiltersPersistsOnRefreshAndKeepsOriginalSubmissionStatusVisible() {
        val value=fixture();memory[owner]=value
        val panel=panel(source{value})
        fun choose(index:Int,expectedSelection:Int) {
            button(panel,"设置完成状态").performClick()
            val list=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView
            assertEquals(expectedSelection,list.checkedItemPosition)
            val colors=(0..2).map{(list.adapter.getView(it,null,list) as TextView).currentTextColor}
            assertEquals(listOf(ModernUi.ChoiceTone.COMPLETE.text,ModernUi.ChoiceTone.INCOMPLETE.text,ModernUi.ChoiceTone.NEUTRAL.text),colors)
            list.performItemClick(null,index,index.toLong())
        }
        choose(0,2)
        assertTrue(texts(panel).contains("未完成 0　已完成 1　已过期 0"))
        button(panel,"已完成").performClick()
        assertTrue(texts(panel).contains("手动标记：已完成 · 仅保存在本机"))
        assertTrue(texts(panel).any{it.startsWith("学习通：未提交")})
        panel.refresh();eventually{texts(panel).any{it.startsWith("更新完成")}}
        assertTrue(texts(panel).contains("手动标记：已完成 · 仅保存在本机"))
        choose(1,0);button(panel,"未完成").performClick()
        assertTrue(texts(panel).contains("手动标记：未完成 · 仅保存在本机"))
        choose(2,1)
        assertFalse(texts(panel).any{it.startsWith("手动标记：")})
        assertTrue(texts(panel).contains("测试作业"))
    }

}
