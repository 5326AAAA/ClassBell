package cn.classbell.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exercises real Android activities with camera-result fixtures and private test ledgers. */
@RunWith(AndroidJUnit4::class)
class DeviceQrActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val course = Course("101", "201", "设备隔离测试课程")
    private val item = SignActivity("301", "设备隔离扫码测试", course, otherId=2)
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun pageText(activity: Activity): String {
        var result = ""
        onMain { result = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return result
    }
    private fun click(activity: Activity, text: String) = onMain {
        val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }
        assertTrue("Button disabled: $text", button.isEnabled)
        button.performClick()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6500
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(15)
        }
        fail(message)
    }
    private fun accounts(count: Int): Pair<BoundAccounts, List<BoundAccount>> {
        var json: String? = null
        val repository = BoundAccounts({ json }, { json = it })
        return repository to (1..count).map { i -> repository.bind("设备测试账号 $i", listOf(Cookie.Builder()
            .name("_uid").value((900+i).toString()).domain("chaoxing.com").path("/").secure().build()), listOf(course)) }
    }
    private fun intent(type: Class<out Activity>) = Intent(context, type)
        .putExtra("courseKey", course.key).putExtra("activeId", item.id).putExtra("title", item.title)
        .putExtra("otherId", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private inline fun isolated(block: (SignLedger) -> Unit) {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val original = settings.all.toMap()
        val name = "codex-device-ui-${UUID.randomUUID()}"
        try { block(SignLedger(context.getSharedPreferences(name, Context.MODE_PRIVATE))) }
        finally {
            context.deleteSharedPreferences(name)
            assertTrue("UI tests changed real app settings", original == settings.all)
        }
    }

    private fun cameraMonitor(scans: AtomicInteger, firstAt: AtomicLong, expiredFirst: Boolean) =
        object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != QrScanActivity::class.java.name) return null
                val index = scans.incrementAndGet()
                firstAt.compareAndSet(0, SystemClock.elapsedRealtime())
                val enc = if (expiredFirst && index == 1) "11111111" else "22222222"
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()
                    .putExtra(QrScanActivity.MATERIAL, "https://mobilelearn.chaoxing.com/widget/sign/e?id=301&enc=$enc")
                    .putExtra(QrScanActivity.CAPTURED_AT, SystemClock.elapsedRealtime()))
            }
        }

    @Test fun actualSingleAccountPageAutomaticallyRescansAfterExpiryAndSubmitsFreshResult() = isolated { ledger ->
        val (repository, bound) = accounts(1)
        val sends = AtomicInteger()
        val scans = AtomicInteger()
        val firstAt = AtomicLong()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.PRE_ON_CREATE && activity is SignActionActivity) {
                activity.boundAccountRepository = repository
                activity.ledgerRepository = ledger
                activity.requirementTrace = {}
                activity.sourceFactory = { guard -> object : SignSource {
                    override fun activities(course: Course) = listOf(item)
                    override fun plan(activity: SignActivity): SignPlan { guard(); return SignPlan(SignMode.QR, "设备隔离测试") }
                    override fun submit(activity: SignActivity, photo: File?, beforeCommit: () -> Unit): SignOutcome = error("Wrong route")
                    override fun submitQr(activity: SignActivity, qr: SignQr, fix: LocationFix?, beforeCommit: () -> Unit): SignOutcome {
                        guard(); qr.requireFor(item); beforeCommit()
                        return if (sends.incrementAndGet() == 1)
                            SignOutcome(false, "二维码已过期，请重新扫描", SignResultCategory.FAILED, retryAllowed=true)
                        else SignOutcome(true, "设备单账号页面已确认")
                    }
                    override fun cancel() {}
                } }
            }
        }
        val monitor = cameraMonitor(scans, firstAt, expiredFirst=true)
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(intent(SignActionActivity::class.java)
                .putExtra("boundAccountId", bound.single().id).putExtra("autoScan", true))
            val page = checkNotNull(activity)
            await("Single-account page did not automatically complete expired then fresh scanning") {
                pageText(page).contains("设备单账号页面已确认")
            }
            assertEquals(2, scans.get())
            assertEquals(2, sends.get())
            assertFalse(pageText(page).contains("确认二维码并提交"))
            val elapsed = SystemClock.elapsedRealtime() - firstAt.get()
            assertTrue("Automatic rescan page exceeded five seconds: $elapsed", elapsed < 5000)
            assertTrue(ledger.attempted(bound.single().id, item))
            reportDeviceEvidence("DEVICE_UI single_expired_rescan=true scans=2 final_submissions=2 elapsed_ms=$elapsed")
        } finally {
            activity?.let { onMain { it.finish() } }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
    }

    @Test fun actualMultiAccountPageContinuesHealthyAccountsWhenOnePreparationFails() = isolated { ledger ->
        val (repository, bound) = accounts(3)
        val failedId = bound.last().id
        val sends = ConcurrentLinkedQueue<String>()
        val overlap = CountDownLatch(2)
        val scans = AtomicInteger()
        val firstAt = AtomicLong()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.PRE_ON_CREATE && activity is MultiAccountSignActivity) {
                activity.accountRepository = repository
                activity.ledgerRepository = ledger
                activity.sourceFactory = { account, guard -> object : SignSource {
                    override fun activities(course: Course) = listOf(item)
                    override fun plan(activity: SignActivity): SignPlan { guard(); return SignPlan(SignMode.QR, "设备多账号隔离测试") }
                    override fun prepareQr(activity: SignActivity): PreparedQr {
                        if (account.id == failedId) throw IOException("Fixture account preparation failure")
                        return super<SignSource>.prepareQr(activity)
                    }
                    override fun submit(activity: SignActivity, photo: File?, beforeCommit: () -> Unit): SignOutcome = error("Wrong route")
                    override fun submitQr(activity: SignActivity, qr: SignQr, fix: LocationFix?, beforeCommit: () -> Unit): SignOutcome {
                        guard(); qr.requireFor(item); beforeCommit(); sends += account.id
                        overlap.countDown()
                        check(overlap.await(3, TimeUnit.SECONDS)) { "Healthy accounts were not sent concurrently" }
                        return SignOutcome(true, "设备多账号页面已确认")
                    }
                    override fun cancel() {}
                } }
            }
        }
        val monitor = cameraMonitor(scans, firstAt, expiredFirst=false)
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(intent(MultiAccountSignActivity::class.java))
            val page = checkNotNull(activity)
            onMain { views(page.window.decorView).filterIsInstance<CheckBox>().forEach { it.performClick() } }
            click(page, "核对所选账号的要求")
            await("Account requirement checks did not finish") { pageText(page).contains("扫码并立即提交所选账号") }
            click(page, "扫码并立即提交所选账号")
            await("Healthy accounts were blocked by the failed account") {
                pageText(page).split("设备多账号页面已确认").size - 1 == 2
            }
            assertEquals(bound.take(2).map { it.id }.toSet(), sends.toSet())
            assertEquals(2, sends.size)
            assertEquals(1, scans.get())
            assertFalse(ledger.attempted(failedId, item))
            val elapsed = SystemClock.elapsedRealtime() - firstAt.get()
            assertTrue("Multi-account UI result exceeded five seconds: $elapsed", elapsed < 5000)
            reportDeviceEvidence("DEVICE_UI healthy_accounts=2 failed_preparation_accounts=1 concurrent=true elapsed_ms=$elapsed")
        } finally {
            activity?.let { onMain { it.finish() } }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
    }
}
