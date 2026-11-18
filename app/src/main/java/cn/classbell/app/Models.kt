package cn.classbell.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class Course(val id: String, val classId: String, val name: String, val teacher:String="",val className:String="",val cpi:String="") {
    val key: String get() = "$id:$classId"
    fun json() = JSONObject().put("id", id).put("classId", classId).put("name", name).put("teacher",teacher).put("className",className).put("cpi",cpi)
    companion object {fun fromJson(value:JSONObject)=Course(value.getString("id"),value.getString("classId"),value.getString("name"),value.optString("teacher"),value.optString("className"),value.optString("cpi"))}
}

enum class ReminderKind(val label: String, val activeType: Int) {
    SIGN_IN("签到", 2), CLASSROOM("课堂活动", 42);
    companion object {
        fun fromType(type: Int) = entries.firstOrNull { it.activeType == type }
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: SIGN_IN
    }
}

data class PendingReminder(val text: String, val kind: ReminderKind = ReminderKind.SIGN_IN)

data class SignActivity(val id: String, val title: String, val course: Course, val kind: ReminderKind = ReminderKind.SIGN_IN, val otherId: Int? = null) {
    val key: String get() = "${course.key}:$id"
    val mayRequirePhoto: Boolean get() = kind == ReminderKind.SIGN_IN && (otherId == null || otherId == 0)
}

class LoginRequired : Exception("登录已失效，请重新登录后再开始监测")
class ApiProblem(message: String) : Exception(message)

object ApiParser {
    fun json(body: String): JSONObject {
        if (body.trimStart().startsWith("<")) {
            if (body.contains("passport2") || body.contains("用户登录")) throw LoginRequired()
            throw ApiProblem("接口返回了网页，暂时无法读取；请稍后重试")
        }
        return try { JSONObject(body) } catch (diagnosticError: Exception) { DiagnosticLog.error("Models", diagnosticError);
            throw ApiProblem("接口响应格式发生变化，未能读取数据")
        }
    }

    private fun check(root: JSONObject) {
        val message = root.optString("msg", root.optString("errorMsg", root.optString("message")))
        if (listOf("未登录", "请登录", "请先登录", "登录失效", "重新登录").any { message.contains(it) }) throw LoginRequired()
        if (root.has("result") && root.optString("result") !in listOf("1", "true")) {
            throw ApiProblem("接口未能返回数据，请重新登录或稍后重试")
        }
    }

    fun courses(body: String): List<Course> {
        val root = json(body)
        check(root)
        val channels = root.optJSONArray("channelList")
            ?: throw ApiProblem("未识别到课程列表，请尝试官方网页登录")
        val result = mutableListOf<Course>()
        fun visit(array: JSONArray) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val content = item.optJSONObject("content") ?: continue
                val data = content.optJSONObject("course")?.optJSONArray("data")
                if (data != null) for (j in 0 until data.length()) {
                    val info = data.optJSONObject(j) ?: continue
                    val id = info.optString("id")
                    val classId = content.optString("id")
                    if (id.isNotBlank() && classId.isNotBlank()) {
                        result += Course(id, classId, info.optString("name", "未命名课程"),info.optString("teacherfactor").ifBlank{info.optString("teacherName")},content.optString("clazzName").ifBlank{content.optString("name")},content.optString("cpi"))
                    }
                }
                content.optJSONArray("channelList")?.let { visit(it) }
                item.optJSONArray("channelList")?.let { visit(it) }
            }
        }
        visit(channels)
        return result.distinctBy { it.key }
    }

    fun activities(body: String, course: Course, now: Long = System.currentTimeMillis()): List<SignActivity> {
        val root = json(body)
        check(root)
        val data = root.optJSONObject("data") ?: throw ApiProblem("未识别到活动数据")
        val array = data.optJSONArray("activeList") ?: throw ApiProblem("未识别到活动列表")
        val result = mutableListOf<SignActivity>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // v2 activelist uses activeType/type for the category. A real ordinary sign
            // has activeType=2, type=2, otherId="0"; otherId must not be used as its category.
            val activityType = if (item.has("activeType") && !item.isNull("activeType"))
                item.optInt("activeType", -1) else item.optInt("type", -1)
            val kind = ReminderKind.fromType(activityType) ?: continue
            if (item.optInt("status", -1) != 1) continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            fun epoch(field: String): Long {
                val n = item.optString(field).toLongOrNull() ?: return 0L
                return if (n in 1_000_000_000L..9_999_999_999L) n * 1000 else n
            }
            val start = epoch("startTime")
            val end = epoch("endTime")
            if ((start > now && start > 0L) || (end > 0L && end <= now)) continue
            result += SignActivity(id, item.optString("nameOne").ifBlank { kind.label }, course, kind, item.optString("otherId").toIntOrNull())
        }
        return result.distinctBy { it.key }
    }
}

object ReminderRules {
    fun unseen(found: List<SignActivity>, acknowledged: Set<String>, pending: Set<String>) =
        found.distinctBy { it.key }.filter { it.key !in acknowledged && it.key !in pending }
    fun retryDelaySeconds(failures: Int, interval: Int): Long =
        (interval.toLong() * (1L shl failures.coerceIn(0, 4))).coerceAtMost(300L)
}

fun accountKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
