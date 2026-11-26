package cn.classbell.app

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Local-only iCalendar export. No signed homework URLs or account identifiers are exported. */
object HomeworkCalendar {
    private val timestamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    fun pending(items:List<HomeworkItem>,now:Long,marks:Map<String,HomeworkMark>,courses:Set<String>?=null)=items
        .filter{(courses==null || it.courseKey in courses) && it.dueAt!=null && it.dueAt>now &&
            it.state(now)==HomeworkState.PENDING && marks[accountKey(it.key)]!=HomeworkMark.COMPLETE}
        .distinctBy{it.key}.sortedBy{it.dueAt}.take(3000)

    internal fun text(value:String)=value.replace("\r\n","\n").replace('\r','\n')
        .filter{it=='\n' || !it.isISOControl()}.take(1200)
        .replace("\\","\\\\").replace("\n","\\n").replace(";","\\;").replace(",","\\,")
    internal fun fold(value:String):String {
        val out=StringBuilder();var octets=0
        value.codePoints().forEach { code->
            val chars=String(Character.toChars(code));val bytes=chars.toByteArray(Charsets.UTF_8).size
            if(octets+bytes>75){out.append("\r\n ");octets=1}
            out.append(chars);octets+=bytes
        }
        return out.toString()
    }
    fun generate(owner:String,items:List<HomeworkItem>,now:Long,alarm:Boolean):ByteArray {
        require(owner.isNotBlank() && items.size<=3000)
        val lines=mutableListOf("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//ClassBell//Homework Calendar//ZH","CALSCALE:GREGORIAN")
        for(item in items.distinctBy{it.key}) {
            val due=item.dueAt ?: continue
            if(due<=now || item.state(now)!=HomeworkState.PENDING)continue
            val uid=MessageDigest.getInstance("SHA-256").digest((owner+"\u0000"+item.key).toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it)}+"@classbell.local"
            lines+=listOf("BEGIN:VEVENT","UID:$uid","DTSTAMP:${timestamp.format(Instant.ofEpochMilli(now))}",
                "DTSTART:${timestamp.format(Instant.ofEpochMilli(due))}","DTEND:${timestamp.format(Instant.ofEpochMilli(due+60_000))}",
                "SUMMARY:${text(item.title)}","DESCRIPTION:${text(item.courseName+"\n作业截止提醒；提交状态请在 ClassBell 更新核对。")}")
            if(alarm)lines+=listOf("BEGIN:VALARM","ACTION:DISPLAY","TRIGGER:-PT30M","DESCRIPTION:作业即将截止","END:VALARM")
            lines+="END:VEVENT"
        }
        lines+="END:VCALENDAR"
        val bytes=(lines.joinToString("\r\n"){fold(it)}+"\r\n").toByteArray(Charsets.UTF_8)
        require(bytes.size<=4*1024*1024){"日历内容过大，请减少课程范围。"}
        return bytes
    }
}
