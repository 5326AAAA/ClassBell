package cn.classbell.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
class CourseMemoryTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val memory get() = app.courseMemory
    private val first = Course("101", "201", "课程甲")
    private val second = Course("102", "202", "课程乙")
    private val courses = listOf(first, second)
    private val userA = accountKey("test-user-a")
    private val userB = accountKey("test-user-b")
    @Before fun reset() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() = reset()

    @Test fun logoutClearsSessionButRestoresSelectionAndTagsForSameAccount() {
        memory.activate(app.prefs, userA, courses)
        app.saveSelectedCourses(setOf(second.key))
        memory.putTag(userA, "周一", setOf(second.key))
        app.prefs.edit().putString("backgroundPhoneType", PhoneFamily.OPPO_ONEPLUS.name).commit()
        memory.detach(app.prefs)
        assertFalse(app.prefs.getBoolean("loggedIn", true))
        assertNull(app.prefs.getString("identity", null))
        assertTrue(app.courses().isEmpty())
        memory.activate(app.prefs, userA, courses.reversed())
        assertEquals(listOf(second), app.selectedCourses())
        assertEquals(setOf(second.key), memory.tags(userA).single().courseKeys)
        assertEquals(PhoneFamily.OPPO_ONEPLUS, BackgroundSettings.selection(app.prefs))
    }

    @Test fun changingAccountsKeepsSelectionsAndTagNamesIsolated() {
        memory.activate(app.prefs, userA, courses)
        app.saveSelectedCourses(setOf(first.key))
        memory.putTag(userA, "上午", setOf(first.key))
        memory.activate(app.prefs, userB, courses)
        assertEquals(courses, app.selectedCourses())
        assertTrue(memory.tags(userB).isEmpty())
        app.saveSelectedCourses(setOf(second.key))
        memory.putTag(userB, "上午", setOf(second.key))
        memory.activate(app.prefs, userA, courses)
        assertEquals(listOf(first), app.selectedCourses())
        assertEquals(setOf(first.key), memory.tags(userA).single().courseKeys)
    }

    @Test fun legacySelectionIsMigratedBeforeLogoutIncludingIntentionalEmptyChoice() {
        app.prefs.edit().putString("identity", userA).putStringSet("selected", emptySet()).commit()
        memory.detach(app.prefs)
        memory.activate(app.prefs, userA, courses)
        assertTrue(app.selectedCourses().isEmpty())
        assertNotNull(memory.selection(userA))
    }

    @Test fun emptyOrPartialRefreshCannotEraseRememberedCourses() {
        memory.activate(app.prefs, userA, courses)
        app.saveSelectedCourses(setOf(second.key))
        memory.activate(app.prefs, userA, emptyList())
        assertTrue(app.selectedCourses().isEmpty())
        memory.activate(app.prefs, userA, listOf(first))
        assertTrue(app.selectedCourses().isEmpty())
        memory.activate(app.prefs, userA, courses)
        assertEquals(listOf(second), app.selectedCourses())
    }

    @Test fun emptyFirstResponseDoesNotSuppressFirstRealCourseList() {
        memory.activate(app.prefs, userA, emptyList())
        assertNull(memory.selection(userA))
        memory.activate(app.prefs, userA, courses)
        assertEquals(courses, app.selectedCourses())
    }

    @Test fun tagsSupportUnionRenameEditingAndDeletionWithoutChangingMonitoring() {
        memory.activate(app.prefs, userA, courses)
        app.saveSelectedCourses(setOf(first.key))
        memory.putTag(userA, "周一", setOf(first.key, "missing:class"))
        memory.putTag(userA, "上午", setOf(second.key))
        memory.putTag(userA, "周一", setOf(second.key), append = true)
        assertEquals(setOf(first.key, second.key), memory.coursesForTags(userA, setOf("周一", "上午"), courses))
        memory.renameTag(userA, "周一", "专业课")
        assertTrue(memory.tags(userA).any { it.name == "专业课" })
        assertThrows(IllegalArgumentException::class.java) { memory.renameTag(userA, "专业课", "上午") }
        memory.putTag(userA, "专业课", setOf(first.key))
        memory.deleteTag(userA, "上午")
        assertEquals(listOf(CourseTag("专业课", setOf(first.key))), memory.tags(userA))
        assertEquals(listOf(first), app.selectedCourses())
    }
}
