package cn.classbell.app

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class CourseDialogsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private var controller: ActivityController<MainActivity>? = null
    private val first = Course("101", "201", "同名课程")
    private val second = Course("101", "202", "同名课程")
    private val identity = accountKey("dialog-test-user")
    @Before fun reset() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
        app.courseMemory.activate(app.prefs, identity, listOf(first, second))
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun main() = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(activity: MainActivity, text: String) = views(activity.window.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == text }
    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()
    private fun ready(dialog: AlertDialog) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = dialog.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1800)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun click(dialog: AlertDialog, which: Int) {
        ready(dialog)
        (views(dialog.window!!.decorView).filterIsInstance<Button>().singleOrNull{it.tag==which} ?: dialog.getButton(which)).performClick(); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun item(dialog: AlertDialog, index: Int) {
        ready(dialog)
        if(dialog.listView!=null)dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
        else views(dialog.window!!.decorView).filterIsInstance<CheckBox>()[index].performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun saveAndTagCreatesGroupFromCheckedCoursesAndDistinguishesSameNameClasses() {
        val activity = main()
        button(activity, "选择课程").performClick()
        var dialog = latest()
        assertTrue(dialog.listView.adapter.getItem(0).toString().contains("201"))
        assertTrue(dialog.listView.adapter.getItem(1).toString().contains("202"))
        item(dialog, 1)
        click(dialog, AlertDialog.BUTTON_NEUTRAL)
        dialog = latest()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("周一")
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(first), app.selectedCourses())
        assertEquals(listOf(CourseTag("周一", setOf(first.key))), app.courseMemory.tags(identity))
    }

    @Test fun tagsCanReplaceOrMergeSelectionAndEmptyTagChoiceDoesNotClearIt() {
        app.courseMemory.putTag(identity, "甲组", setOf(first.key))
        app.courseMemory.putTag(identity, "乙组", setOf(second.key))
        app.saveSelectedCourses(setOf(first.key))
        val activity = main()
        button(activity, "按标签选择课程").performClick()
        var dialog = latest()
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertTrue(dialog.isShowing)
        assertEquals(listOf(first), app.selectedCourses())
        val secondIndex = views(dialog.window!!.decorView).filterIsInstance<CheckBox>().indexOfFirst{it.text.toString().startsWith("乙组")}
        item(dialog, secondIndex)
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(second), app.selectedCourses())
        button(activity, "按标签选择课程").performClick()
        dialog = latest()
        val firstIndex = views(dialog.window!!.decorView).filterIsInstance<CheckBox>().indexOfFirst{it.text.toString().startsWith("甲组")}
        item(dialog, firstIndex)
        click(dialog, AlertDialog.BUTTON_NEUTRAL)
        assertEquals(listOf(first, second), app.selectedCourses())
    }

    @Test fun manageTagsRenamesAndDeletesTagWithoutChangingSelectedCourses() {
        app.courseMemory.putTag(identity, "旧标签", setOf(first.key))
        val activity = main()
        button(activity, "管理课程标签").performClick()
        item(latest(), 0)
        item(latest(), 1)
        var dialog = latest()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("新标签")
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals("新标签", app.courseMemory.tags(identity).single().name)
        button(activity, "管理课程标签").performClick()
        item(latest(), 0)
        item(latest(), 2)
        assertTrue(app.courseMemory.tags(identity).isEmpty())
        assertEquals(listOf(first, second), app.selectedCourses())
    }
}
