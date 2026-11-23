package cn.classbell.app

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class QrCourseLookupTest {
    private val a = Course("1", "11", "甲")
    private val b = Course("2", "22", "乙")
    private val courses = listOf(a, b)

    @Test fun matchingActivityWinsAfterAnotherCourseFailed() {
        val failure = IOException()
        val errors = mutableListOf<Exception>()
        val expected = SignActivity("40", "签到", b)
        assertSame(expected, QrCourseLookup.find("40", courses, {}, {
            if (it == a) throw failure else listOf(expected)
        }, errors::add))
        assertTrue(errors.isEmpty() || errors == listOf(failure))
    }

    @Test fun noMatchingActivityIsDifferentFromPartialFailure() {
        val missing = assertThrows(ApiProblem::class.java) {
            QrCourseLookup.find("40", courses, {}, { emptyList() }, {})
        }
        assertTrue(missing.message!!.contains("未找到"))
        val partial = assertThrows(ApiProblem::class.java) {
            QrCourseLookup.find("40", courses, {}, { if (it == a) throw IOException() else emptyList() }, {})
        }
        assertTrue(partial.message!!.contains("另有 1 门查询失败"))
    }

    @Test fun emptyCacheIsDifferentFromAllRequestsFailing() {
        val empty = assertThrows(ApiProblem::class.java) {
            QrCourseLookup.find("40", emptyList(), {}, { fail("No activity requests expected"); emptyList() }, {})
        }
        assertTrue(empty.message!!.contains("没有本地课程"))
        var logged = 0
        val failed = assertThrows(ApiProblem::class.java) {
            QrCourseLookup.find("40", courses, {}, { throw IOException() }, { logged++ })
        }
        assertEquals(2, logged)
        assertTrue(failed.message!!.contains("查询均失败"))
    }

    @Test fun cancellationAndLoginExpiryAreNotCountedAsFailedCourses() {
        for (e in listOf(CancellationException(), LoginRequired())) {
            val thrown = assertThrows(e.javaClass) {
                QrCourseLookup.find("40", listOf(a), {}, { throw e }, { fail("Unexpected logged failure") })
            }
            assertSame(e, thrown)
        }
    }

    @Test fun slowQueriesDoNotBlockTheMatchOrStartTheFourthCourse() {
        val slowStarted = java.util.concurrent.CountDownLatch(2)
        val slowCancelled = java.util.concurrent.CountDownLatch(2)
        val neverReleased = java.util.concurrent.CountDownLatch(1)
        val c = Course("3", "33", "丙")
        val d = Course("4", "44", "丁")
        val started = java.util.concurrent.ConcurrentLinkedQueue<Course>()
        val hit = SignActivity("40", "签到", c)
        assertSame(hit, QrCourseLookup.find("40", courses + listOf(c,d), {}, { course ->
            started.add(course)
            if (course == c) {
                check(slowStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
                listOf(hit)
            } else {
                slowStarted.countDown()
                try { neverReleased.await(3, java.util.concurrent.TimeUnit.SECONDS) }
                catch (_: InterruptedException) { slowCancelled.countDown() }
                emptyList()
            }
        }, {}))
        assertTrue(slowCancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf(a,b,c), started.toSet())
    }
}
