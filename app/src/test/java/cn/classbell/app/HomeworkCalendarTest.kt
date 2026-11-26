package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkCalendarTest {
    private val now=Instant.parse("2026-11-26T02:00:00Z").toEpochMilli()
    private fun item(id:String="1")=HomeworkItem(id,"作业一","课程", "course-a","未提交","",now+3_600_000,now,false,"https://example.invalid/?secret=hidden")
    @Test fun onlyFuturePendingAndNotManuallyCompleteInSelectedCourseAreExported() {
        val all=listOf(item(),item("2").copy(dueAt=null),item("3").copy(dueAt=now-1),item("4").copy(sourceStatus="已提交"),
            item("5"),item("6").copy(courseKey="course-b"),item())
        val marks=mapOf(accountKey(item("5").key) to HomeworkMark.COMPLETE)
        assertEquals(listOf(item()),HomeworkCalendar.pending(all,now,marks,setOf("course-a")))
    }
    @Test fun utcTimesAlarmsAndStableAccountIsolatedUids() {
        fun content(owner:String,time:Long=now,alarm:Boolean=true)=HomeworkCalendar.generate(owner,listOf(item()),time,alarm).toString(Charsets.UTF_8)
        fun uid(text:String)=text.lineSequence().single{it.startsWith("UID:")}
        val value=content("a")
        assertTrue(value.contains("DTSTART:20261126T030000Z\r\n"));assertTrue(value.contains("TRIGGER:-PT30M"))
        assertEquals(uid(value),uid(content("a",now+1000)));assertNotEquals(uid(value),uid(content("b")))
        assertFalse(content("a",alarm=false).contains("VALARM"));assertFalse(value.contains("secret"));assertFalse(value.contains("course-a"))
    }
    @Test fun newlineInjectionAndLongUnicodeAreEscapedAndFoldedWithin75Octets() {
        val title="中文😀".repeat(80)+"\r\nEND:VEVENT;X,\\"
        val data=HomeworkCalendar.generate("a",listOf(item().copy(title=title)),now,false).toString(Charsets.UTF_8)
        assertEquals(1,data.lineSequence().count{it=="END:VEVENT"})
        assertTrue(data.contains("\\nEND:VEVENT\\;X\\,\\\\"))
        for(line in data.split("\r\n"))assertTrue("Oversized line",line.toByteArray(Charsets.UTF_8).size<=75)
        assertFalse(data.contains('\uFFFD'));assertTrue(data.endsWith("END:VCALENDAR\r\n"))
    }
    @Test fun emptyCalendarAndBlankOwnerValidation() {
        assertFalse(HomeworkCalendar.generate("a",emptyList(),now,true).toString(Charsets.UTF_8).contains("VEVENT"))
        assertThrows(IllegalArgumentException::class.java){HomeworkCalendar.generate("",emptyList(),now,false)}
    }
}
