package cn.classbell.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class AccountStateTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private var controller: ActivityController<MainActivity>? = null
    @Before fun reset() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { controller?.pause()?.stop()?.destroy(); reset() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun main() = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
    private fun button(activity: MainActivity, text: String) = views(activity.window.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == text }
    private fun password(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<EditText>()
        .single { it.hint == "学习通密码" }
    private fun labels(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun loginFixture() = app.courseMemory.activate(app.prefs, accountKey("state-test"), listOf(Course("101", "201", "测试课程")))

    @Test fun savedLoginShowsLoggedInSummaryAndHidesFormUntilRequested() {
        loginFixture()
        val activity = main()
        assertTrue(labels(activity).contains("已登录"))
        assertTrue(labels(activity).any { it.contains("1 门课程") })
        assertEquals(View.GONE, (password(activity).parent as View).visibility)
        button(activity, "添加账号").performClick()
        assertEquals(View.VISIBLE, (password(activity).parent as View).visibility)
        button(activity, "收起登录").performClick()
        controller!!.recreate()
        assertEquals(View.GONE, (password(controller!!.get()).parent as View).visibility)
    }

    @Test fun loginCompletionCollapsesFormAndSessionInvalidationShowsLoginAgain() {
        val activity = main()
        assertTrue(labels(activity).contains("未登录"))
        assertEquals(View.VISIBLE, (password(activity).parent as View).visibility)
        loginFixture(); app.notifyUi(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(labels(activity).contains("已登录"))
        assertEquals(View.GONE, (password(activity).parent as View).visibility)
        app.prefs.edit().putBoolean("loggedIn", false).commit()
        app.notifyUi(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(labels(activity).contains("登录已失效"))
        assertEquals(View.VISIBLE, (password(activity).parent as View).visibility)
    }

    @Test fun logoutStateRestoresFormAndDoesNotClaimSignedIn() {
        loginFixture()
        val activity = main()
        app.courseMemory.detach(app.prefs); app.notifyUi(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(labels(activity).contains("未登录"))
        assertFalse(labels(activity).contains("已登录"))
        assertEquals(View.VISIBLE, (password(activity).parent as View).visibility)
        assertEquals(View.GONE, button(activity, "添加账号").visibility)
    }
}
