package cn.classbell.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BackendSettingsTest {
    private fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup)(0 until root.childCount).flatMap{views(root.getChildAt(it))}else emptyList()
    @Test fun backendOptInRequiresAnAccountAndOnlyChangesConfigurationBeforeMonitoringStarts() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        app.prefs.edit().clear().commit();app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val toggle=views(controller.get().window.decorView).filterIsInstance<CheckBox>().single{it.text.toString()=="直接获取签到动态（测试中）"}
            assertFalse(toggle.isChecked);toggle.performClick()
            assertFalse(app.prefs.getBoolean("backendPush",false));assertFalse(toggle.isChecked)
            app.courseMemory.activate(app.prefs,accountKey("synthetic-im-account"),listOf(Course("101","201","合成课程")))
            app.notifyUi();shadowOf(Looper.getMainLooper()).idle()
            toggle.performClick()
            assertTrue(app.prefs.getBoolean("backendPush",false));assertFalse(app.prefs.getBoolean("monitorEnabled",false))
            val words=views(controller.get().window.decorView).filterIsInstance<android.widget.TextView>().joinToString("\n"){it.text}
            assertTrue(words.contains("不用等学习通发手机通知"));assertTrue(words.contains("真实签到 / 课堂活动提醒：尚未验证"))
            assertTrue(words.contains("开始监测"));assertFalse(words.contains("IM"));assertFalse(words.contains("SockJS"))
            app.prefs.edit().putBoolean("backend.connected",true).putString("backend.owner",accountKey("synthetic-im-account"))
                .putString("backend.status","消息服务已连上").commit()
            app.notifyUi();shadowOf(Looper.getMainLooper()).idle()
            val cachedWords=views(controller.get().window.decorView).filterIsInstance<android.widget.TextView>().joinToString("\n"){it.text}
            assertFalse(cachedWords.contains("消息服务已连上"))
            assertTrue(cachedWords.contains("回首页点“开始监测”后连接"))
            toggle.performClick();assertFalse(app.prefs.getBoolean("backendPush",true))
        } finally {controller.pause().stop().destroy();app.prefs.edit().clear().commit();app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()}
    }
}
