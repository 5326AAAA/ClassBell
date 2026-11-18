package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.CancellationException

enum class HomeworkState(val label: String) { PENDING("待交"), SUBMITTED("已交"), EXPIRED("已过期"), UNKNOWN("待确认") }

data class HomeworkItem(
    val id: String, val title: String, val courseName: String, val courseKey: String,
    val sourceStatus: String, val sourceTime: String, val dueAt: Long?, val fetchedAt: Long,
    val peerReview: Boolean, val url: String, val deadlineCheckedAt:Long?=null, val activityTask:Boolean=false
) {
    val key get() = if(activityTask)"activity-task:$id" else "$courseKey:$id:${if (peerReview) "review" else "work"}"
    fun urgent(now:Long) = !peerReview && state(now)==HomeworkState.PENDING && dueAt!=null && dueAt>now && dueAt-now<=86_400_000L
    fun state(now: Long): HomeworkState {
        val status = sourceStatus.replace(Regex("\\s+"), "")
        if (listOf("未提交", "未交", "待提交", "未完成", "待互评", "未互评", "待完成").none { status.contains(it) } &&
            listOf("已完成", "待批阅", "待批改", "已批阅", "已批改", "已提交", "已交", "已互评").any { status.contains(it) })
            return HomeworkState.SUBMITTED
        if (listOf("已截止", "已过期", "已结束", "逾期", "过期未交").any { (sourceStatus + sourceTime).contains(it) }) return HomeworkState.EXPIRED
        val pending = listOf("未提交", "未交", "待提交", "未完成", "待互评", "未互评", "待完成").any { status.contains(it) }
        if (pending && dueAt != null && now >= dueAt) return HomeworkState.EXPIRED
        return if (pending) HomeworkState.PENDING else HomeworkState.UNKNOWN
    }
    fun remaining(now: Long): String {
        if (state(now) == HomeworkState.SUBMITTED) return "${if (peerReview) "互评已完成" else "已提交"}${if (sourceStatus.isBlank()) "" else " · $sourceStatus"}"
        if (state(now) == HomeworkState.EXPIRED) return "提交时间已过，是否允许补交请以学习通为准"
        val due = dueAt ?: return sourceTime.ifBlank { "截止时间未读取，请查看作业详情核对" }
        val minutes = ((due - now).coerceAtLeast(0) + 59_999) / 60_000
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        val mins = minutes % 60
        return "约剩 " + (if (days > 0) "${days}天 " else "") + (if (hours > 0 || days > 0) "${hours}小时 " else "") + "${mins}分钟"
    }
    fun deadlineText():String?=dueAt?.let{(if(sourceTime.startsWith("上次读取"))"上次读取的截止时间：" else if(HomeworkParser.remainingMillis(sourceTime)!=null)"预计截止：" else "截止：")+HomeworkDeadline.format(it)+"（北京时间）"}
    fun json() = JSONObject().put("id", id).put("title", title).put("courseName", courseName).put("courseKey", courseKey)
        .put("status", sourceStatus).put("time", sourceTime).put("dueAt", dueAt ?: JSONObject.NULL)
        .put("fetchedAt", fetchedAt).put("review", peerReview).put("url", url).put("deadlineCheckedAt",deadlineCheckedAt ?: JSONObject.NULL).put("activityTask",activityTask)
}

data class HomeworkSnapshot(val fetchedAt: Long, val items: List<HomeworkItem>, val skippedRows: Int = 0, val retainedKeys: Set<String> = emptySet()) {
    fun json() = JSONObject().put("fetchedAt", fetchedAt).put("skippedRows", skippedRows)
        .put("retainedKeys", JSONArray(retainedKeys.toList()))
        .put("items", JSONArray().also { a -> items.forEach { a.put(it.json()) } }).toString()
    companion object {
        fun fromJson(text: String): HomeworkSnapshot {
            val root = JSONObject(text); val array = root.getJSONArray("items")
            require(array.length() <= 3000)
            return HomeworkSnapshot(root.getLong("fetchedAt"), (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                HomeworkItem(item.getString("id"), item.getString("title"), item.getString("courseName"), item.getString("courseKey"),
                    item.getString("status"), item.getString("time"), if (item.isNull("dueAt")) null else item.getLong("dueAt"),
                    item.getLong("fetchedAt"), item.optBoolean("review"), item.getString("url"),if(item.isNull("deadlineCheckedAt"))null else item.optLong("deadlineCheckedAt").takeIf{it>0},item.optBoolean("activityTask"))
            }, root.optInt("skippedRows", 0).coerceAtLeast(0), root.optJSONArray("retainedKeys")?.let { keys ->
                require(keys.length() <= 3000)
                (0 until keys.length()).map { keys.getString(it) }.toSet()
            }.orEmpty())
        }
    }
}

object HomeworkParser {
    private fun rejected(code: String, message: String): Nothing {
        DiagnosticLog.event("HomeworkParse", "rejected reason=$code", "WARN")
        throw ApiProblem(message)
    }
    const val LIST_URL = "https://mooc1-api.chaoxing.com/work/stu-work"
    const val WEB_URL = "https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/all-task"
    private val timePattern = Regex("(?:剩余|还有)\\s*(?:(\\d+)\\s*天)?\\s*(?:(\\d+)\\s*小时)?\\s*(?:(\\d+)\\s*分钟)?\\s*(?:(\\d+)\\s*秒)?")
    fun remainingMillis(text: String): Long? {
        val match = timePattern.find(text) ?: return null
        if (match.groupValues.drop(1).all { it.isBlank() }) return null
        val values = match.groupValues.drop(1).map { if (it.isBlank()) 0L else it.toLongOrNull() ?: return null }
        if (values.any { it > 10_000_000 }) return null
        return (values[0] * 86400 + values[1] * 3600 + values[2] * 60 + values[3]) * 1000
    }
    fun safeUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        if(activityTaskUrl(value))return true
        return url.isHttps && url.port == 443 && url.fragment == null && url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("mooc1-api.chaoxing.com", "mooc1.chaoxing.com", "mooc1-2.chaoxing.com", "mooc2-ans.chaoxing.com") &&
            (url.encodedPath in setOf("/work/stu-work", "/mooc-ans/mooc2/work/all-task", "/mooc-ans/android/mtaskmsgspecial",
                "/mooc-ans/mooc2/work/task", "/mooc-ans/work/phone/eval-list") || HomeworkReadPolicy.allowed(value))
    }
    fun activityTaskUrl(value:String):Boolean {
        val url=value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port==443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null &&
            url.host=="mobilelearn.chaoxing.com" && url.encodedPath=="/widget/renwu/proxyRenwuAttendUrl" &&
            url.queryParameterValues("activeId").size==1 && SignProtocol.validId(url.queryParameter("activeId").orEmpty())
    }
    private fun normalizedUrl(value: String): String? {
        val url = LIST_URL.toHttpUrlOrNull()!!.resolve(value.trim()) ?: return null
        return (if (url.scheme == "http" && url.port == 80) url.newBuilder().scheme("https").port(443).build() else url).toString()
    }
    private fun urlReason(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return "MALFORMED_URL"
        return when {
            !url.isHttps -> "NON_HTTPS_URL"
            url.port != 443 -> "UNSUPPORTED_PORT"
            url.username.isNotEmpty() || url.password.isNotEmpty() -> "URL_USER_INFO"
            url.fragment != null -> "URL_FRAGMENT"
            url.host !in setOf("mooc1-api.chaoxing.com", "mooc1.chaoxing.com", "mooc1-2.chaoxing.com", "mooc2-ans.chaoxing.com") -> "UNSUPPORTED_HOST"
            else -> "UNSUPPORTED_PATH"
        }
    }
    fun parse(html: String, courses: List<Course>, now: Long): HomeworkSnapshot {
        val doc = Jsoup.parse(html, LIST_URL)
        val text = doc.body().text()
        if (doc.selectFirst("input[type=password]") != null || doc.title().contains("登录") ||
            text.contains("请先登录") || text.contains("登录已失效")) throw LoginRequired()
        if (text.contains("无权限的操作") || text.contains("访问频繁")) throw ApiProblem("学习通暂未允许读取作业，请打开官方作业栏核对登录状态。")
        val rows = doc.select("li[data]")
        DiagnosticLog.event("HomeworkParse", "rows=${rows.size} login_form=${doc.selectFirst("input[type=password]")!=null} chars=${html.length}")
        if (rows.isEmpty()) {
            if (listOf("暂无作业", "没有作业", "暂无相关", "暂无任务").any { text.contains(it) }) return HomeworkSnapshot(now, emptyList())
            rejected("UNRECOGNIZED_LIST_FORMAT", "作业页面格式发生变化，未将读取失败当作没有作业；请打开官方作业栏查看。")
        }
        if (rows.size > 3000) rejected("TOO_MANY_ROWS", "作业记录过多，请先在官方作业栏查看。")
        var skipped = 0
        val items = rows.mapIndexedNotNull { index, row ->
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val rawLink = row.attr("data")
            val link = if (rawLink.isBlank()) "" else normalizedUrl(rawLink).orEmpty()
            val url = link.toHttpUrlOrNull()
            val content = row.selectFirst("div[role=option], .right-content") ?: row
            val title = content.selectFirst("p")?.text()?.trim().orEmpty()
            val spans = content.select("span").map { it.text().trim() }.filter { it.isNotBlank() }
            val status = content.selectFirst(".status, .stuStatus")?.text()?.trim()
                ?: spans.firstOrNull { !it.startsWith("《") && !it.startsWith("剩余") }.orEmpty()
            val time = row.selectFirst(".time, .fr")?.text()?.trim().orEmpty()
            val absolute=HomeworkDeadline.read(row.outerHtml(),now)
            val courseId = url?.queryParameter("courseId").orEmpty()
            val classId = (url?.queryParameter("clazzId") ?: url?.queryParameter("classId")).orEmpty()
            val task=activityTaskUrl(link)
            val visibleCourse=(content.selectFirst(".courseName")?.attr("title")?.takeIf{it.isNotBlank()}
                ?: content.selectFirst(".courseName")?.text() ?: spans.firstOrNull { it.startsWith("《") }).orEmpty().removePrefix("《").removeSuffix("》")
            val prefix=visibleCourse.removeSuffix("...").removeSuffix("…").trim()
            val candidates=if(task && prefix.isNotBlank())courses.filter{it.name==visibleCourse || ((visibleCourse.endsWith("...") || visibleCourse.endsWith("…")) && it.name.startsWith(prefix))} else emptyList()
            val course = courses.firstOrNull { it.key == "$courseId:$classId" } ?: candidates.singleOrNull()
            val courseKey = course?.key ?: if(task)"task:${DiagnosticLog.reference(visibleCourse)}" else "$courseId:$classId"
            val courseName = course?.name ?: visibleCourse.ifBlank { "未标注课程" }
            val id = (if(task)url?.queryParameter("activeId") else url?.queryParameter("taskrefId") ?: url?.queryParameter("workId") ?: url?.queryParameter("workRelationId")).orEmpty()
            val queryKeys = url?.queryParameterNames?.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,32}")) }?.take(20)?.joinToString(",").orEmpty()
            val context = "row=${index + 1}/${rows.size} course=${DiagnosticLog.label(courseName)} work=${DiagnosticLog.label(title)} course_ref=${DiagnosticLog.reference(courseKey)} work_ref=${DiagnosticLog.reference(id)} endpoint=${DiagnosticLog.endpoint(link)} query_keys=$queryKeys"
            val reason = when {
                rawLink.isBlank() -> "EMPTY_ROW_URL"
                !safeUrl(link) -> urlReason(link)
                title.isBlank() -> "MISSING_TITLE"
                status.isBlank() -> "MISSING_STATUS"
                id.isBlank() -> "MISSING_WORK_ID"
                else -> null
            }
            if (reason != null) {
                skipped++
                DiagnosticLog.event("HomeworkRow", "$context skipped reason=$reason course_id_present=${courseId.isNotBlank()} class_id_present=${classId.isNotBlank()} work_id_present=${id.isNotBlank()} has_content=${content !== row}", "WARN")
                return@mapIndexedNotNull null
            }
            DiagnosticLog.event("HomeworkRow", "$context parsed status=${DiagnosticLog.label(status)} deadline_found=${absolute != null} relative_deadline=${remainingMillis(time) != null} normalized=${rawLink != link}")
            HomeworkItem(id, title.take(300), courseName.take(200), courseKey, status.take(100), absolute?.text ?: time.take(200),
                absolute?.at ?: remainingMillis(time)?.let { now + it }, now, url!!.encodedPath.contains("eval-list"), link,activityTask=task)
        }
        if (items.isEmpty() && skipped > 0) rejected("ALL_ROWS_REJECTED", "本次作业记录均未能识别，已保留旧数据；请在“我的”导出日志。")
        val unique = items.distinctBy { it.key }
        DiagnosticLog.event("HomeworkParse", "summary rows=${rows.size} accepted=${unique.size} skipped=$skipped duplicates=${items.size - unique.size}")
        return HomeworkSnapshot(now, unique, skipped)
    }
}
