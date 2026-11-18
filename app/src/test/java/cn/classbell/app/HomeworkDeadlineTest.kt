package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkDeadlineTest {
    private fun epoch(value:String)=Instant.parse(value).toEpochMilli()
    private val now=epoch("2026-11-05T04:56:00Z")
    @Test fun readsTheTwoReportedDateLayoutsAndExpiresOnlyUnsubmittedWork(){
        for((start,end,expected) in listOf(Triple("06-02 18:00","06-09 20:00","2026-06-09T12:00:00Z"),Triple("10-26 14:38","10-30 15:48","2026-10-30T07:48:00Z"))) {
            val due=HomeworkDeadline.read("<div>开始时间：$start</div><div>截止时间：$end</div>",now)!!
            assertEquals(epoch(expected),due.at)
            val item=HomeworkItem("1","合成作业","合成课程","1:2","未提交",due.text,due.at,now,false,HomeworkParser.WEB_URL)
            assertEquals(HomeworkState.EXPIRED,item.state(now));assertFalse(item.urgent(now))
            assertTrue(item.deadlineText()!!.contains("截止：2026-"))
            assertEquals(HomeworkState.SUBMITTED,item.copy(sourceStatus="待批阅").state(now))
        }
    }
    @Test fun explicitYearHiddenFieldsAndEpochTakePriorityOverYearlessDisplay(){
        val expected=epoch("2025-06-09T12:00:00Z")
        val visible="<p>开始时间：06-02 18:00</p><p>截止时间：06-09 20:00</p>"
        assertEquals(expected,HomeworkDeadline.read("<input id=endTime value='2025-06-09 20:00'>$visible",now)!!.at)
        assertEquals(expected,HomeworkDeadline.read("<script>var endTime=$expected;</script>$visible",now)!!.at)
        assertEquals(expected,HomeworkDeadline.read("<li data-end-time='${expected/1000}'>$visible</li>",now)!!.at)
        assertEquals(expected,HomeworkDeadline.time("2025-06-09T12:00:00Z",now))
        assertEquals(expected,HomeworkDeadline.read("<span id=endTime>06-09 20:00</span><script>var endTime='2025-06-09T12:00:00Z';</script>",now)!!.at)
    }
    @Test fun calendarValidationAndCrossYearRulesDoNotTurnStartTimeIntoDeadline(){
        assertNull(HomeworkDeadline.read("<p>开始时间：2026-10-30 15:48</p>",now))
        assertNull(HomeworkDeadline.read("截止时间：不限时",now))
        assertNull(HomeworkDeadline.time("2026-02-30 15:48",now))
        assertNull(HomeworkDeadline.time("2026-10-30 25:48",now))
        assertEquals(epoch("2027-01-03T12:00:00Z"),HomeworkDeadline.time("01-03 20:00",epoch("2026-12-30T00:00:00Z"),"12-28 18:00"))
        assertEquals(epoch("2026-01-03T12:00:00Z"),HomeworkDeadline.time("01-03 20:00",epoch("2026-01-02T00:00:00Z"),"12-28 18:00"))
        assertNull(HomeworkDeadline.time("01-03 20:00",now,"12-28 18:00"))
    }
    @Test fun absoluteListDatesWorkWithoutCountdownAndSurviveCacheRoundTrip(){
        val html="""<li data="https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/task?courseId=1&amp;classId=2&amp;workId=3"><div role=option><p>固定日期作业</p><span class=status>未提交</span><span class=time>截止时间：2026-10-30 15:48</span></div></li>"""
        val snapshot=HomeworkParser.parse(html,emptyList(),now)
        assertEquals(HomeworkState.EXPIRED,snapshot.items.single().state(now))
        assertEquals(snapshot,HomeworkSnapshot.fromJson(snapshot.json()))
        val checked=snapshot.copy(items=snapshot.items.map{it.copy(deadlineCheckedAt=now)})
        assertEquals(checked,HomeworkSnapshot.fromJson(checked.json()))
    }
}
