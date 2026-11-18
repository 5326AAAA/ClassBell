package cn.classbell.app

import org.jsoup.Jsoup
import java.time.*
import java.time.format.DateTimeFormatter

data class HomeworkDue(val at:Long,val text:String)

/** Reads published metadata only. It never opens an answer/submit action or runs page scripts. */
object HomeworkDeadline {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val names=setOf("endtime","enddate","deadline","workendtime","workenddate","worktimesend","worktimesendlong","endtimestamp","endtimelong")
    private const val date="(?:\\d{4}[-/年])?\\d{1,2}[-/月]\\d{1,2}日?[T\\s]+\\d{1,2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2})?"
    private val parts=Regex("(?:(\\d{4})[-/年])?(\\d{1,2})[-/月](\\d{1,2})日?[T\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private fun normalized(value:String)=value.lowercase().replace("-","").replace("_","")
    fun read(html:String,now:Long):HomeworkDue? {
        val doc=Jsoup.parse(html);val text=doc.body().text()
        if(doc.selectFirst("input[type=password]")!=null || doc.title().contains("登录") || text.contains("请先登录") || text.contains("登录已失效"))throw LoginRequired()
        if(listOf("请完成安全验证","请先完成验证","访问频繁","无权限的操作").any(text::contains))throw ApiProblem("此作业详情暂不能读取，请在学习通核对。")
        val begin=Regex("(?:开始|起始|发布)\\s*(?:时间|日期)?\\s*[:：]?\\s*($date)").find(text)?.groupValues?.get(1)
        fun resolved(raw:String):HomeworkDue?=time(raw,now,begin)?.let{HomeworkDue(it,"截止："+if(raw.trim().matches(Regex("[0-9]{10}|[0-9]{13}")))format(it) else raw.trim())}
        val candidates=mutableListOf<String>()
        // Hidden/data fields often retain the full year even if the visible page shows MM-dd.
        for(element in doc.allElements.take(4000)) {
            if(normalized(element.attr("name")) in names || normalized(element.id()) in names)
                candidates+=element.attr("value").ifBlank{element.text()}
            for(attribute in element.attributes())if(normalized(attribute.key.removePrefix("data-")) in names)
                candidates+=attribute.value
        }
        val script=Regex("(?<![a-zA-Z0-9_])[\"']?(?:endTime|endDate|deadline|workEndTime|workTimesEndLong|workTimesEnd)[\"']?\\s*[:=]\\s*[\"']?($date|[0-9]{13}|[0-9]{10})",RegexOption.IGNORE_CASE)
        for(node in doc.select("script"))for(match in script.findAll(node.data()).take(32))candidates+=match.groupValues[1]
        val visible=Regex("(?:截止|截至|结束)\\s*(?:时间|日期)?\\s*[:：]?\\s*($date)").find(text)?.groupValues?.get(1)
        visible?.let{candidates+=it}
        for(value in candidates.sortedBy{if(it.trim().matches(Regex("[0-9]{10}|[0-9]{13}")) || Regex("^[0-9]{4}[-/年]").containsMatchIn(it.trim()))0 else 1})resolved(value)?.let{return it}
        return null
    }
    fun time(value:String,now:Long,begin:String?=null):Long? {return runCatching {
        val raw=value.trim()
        if(raw.matches(Regex("[0-9]{10}|[0-9]{13}"))) {
            val epoch=raw.toLong()*(if(raw.length==10)1000 else 1)
            return epoch.takeIf{Instant.ofEpochMilli(it).atZone(zone).year in 2000..2200}
        }
        // A local date is another supported format, so a failed offset probe is normal.
        runCatching{OffsetDateTime.parse(raw).takeIf{it.year in 2000..2200}?.toInstant()?.toEpochMilli()}.getOrNull()?.let{return it}
        val match=parts.matchEntire(raw) ?: return null
        val current=Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        val values=match.groupValues
        fun at(year:Int):LocalDateTime {require(year in 2000..2200);return LocalDateTime.of(year,values[2].toInt(),values[3].toInt(),values[4].toInt(),values[5].toInt(),values[6].ifBlank{"0"}.toInt())}
        var year=values[1].toIntOrNull() ?: current.year
        val start=begin?.let(parts::matchEntire)
        if(values[1].isBlank() && start!=null) {
            val s=start.groupValues
            val startYear=s[1].toIntOrNull()
            fun startAt(y:Int)=LocalDateTime.of(y,s[2].toInt(),s[3].toInt(),s[4].toInt(),s[5].toInt(),s[6].ifBlank{"0"}.toInt())
            if(startYear!=null)year=startYear+if(at(startYear)<startAt(startYear))1 else 0
            else if(at(year)<startAt(year)) {
                // A yearless cross-year interval is usable when it contains this refresh date.
                val anchor=listOf(current.year-1,current.year).firstOrNull{current>=startAt(it) && current<=at(it+1)} ?: return null
                year=anchor+1
            }
        }
        at(year).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()}
    fun format(at:Long)=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(zone))
}
