package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class HomeworkSelectionTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private fun item(key:String="1:2",due:Long?=86_400_001,status:String="未提交",review:Boolean=false)=HomeworkItem("1","合成作业","合成课程",key,status,"",due,0,review,HomeworkParser.WEB_URL)
    @Test fun deadlineBoundaryAndStatePreventFalseRedCards() {
        assertTrue(item(due=86_400_001).urgent(1))
        assertFalse(item(due=86_400_002).urgent(1))
        assertTrue(item(due=2).urgent(1))
        assertFalse(item(due=1).urgent(1))
        assertFalse(item(due=null).urgent(1))
        assertFalse(item(status="已提交").urgent(1))
        assertFalse(item(review=true).urgent(1))
        assertFalse(item(status="未知状态").urgent(1))
    }
    @Test fun allEmptyAndSelectedCoursesRemainIndependentPerAccountAndSurviveRecreation() {
        val prefs=app.prefs;prefs.edit().clear().putStringSet("selected",setOf("monitor-only")).commit()
        val filter=HomeworkCourseFilter(prefs);val items=listOf(item(),item("3:4"))
        assertEquals(2,filter.apply("a",items).size)
        filter.save("a",setOf("1:2"))
        assertEquals(listOf("1:2"),HomeworkCourseFilter(prefs).apply("a",items).map{it.courseKey})
        assertEquals(2,filter.apply("b",items).size)
        filter.save("b",emptySet());assertTrue(filter.apply("b",items).isEmpty())
        filter.save("a",null);assertEquals(2,filter.apply("a",items).size)
        assertEquals(setOf("monitor-only"),prefs.getStringSet("selected",null))
        prefs.edit().clear().commit()
    }
}
