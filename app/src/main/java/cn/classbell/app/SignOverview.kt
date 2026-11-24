package cn.classbell.app

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SignOverview(
    val name: String, val type: String, val status: Int,
    val startsAt: Long?, val publishedAt: Long?, val endsAt: Long?, val attendedCount: Int?
) {
    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
        fun format(at: Long): String = formatter.format(Instant.ofEpochMilli(at))

        fun read(data: JSONObject, item: SignActivity): SignOverview? {
            fun field(vararg keys: String): String? = keys.mapNotNull { key ->
                if(data.isNull(key)) null else data.optString(key).trim().takeIf { it.isNotEmpty() }
            }.distinct().let { if(it.size>1) "CONFLICT" else it.singleOrNull() }
            if(field("id","activeId")!=item.id || field("activeType","type")!="2") return null
            if(field("courseId","courseid")?.let{it!=item.course.id}==true ||
                field("clazzid","clazzId","classId")?.let{it!=item.course.classId}==true) return null
            fun time(vararg keys: String): Long? {
                val number = field(*keys)?.toLongOrNull() ?: return null
                val millis = if(number in 1_000_000_000L..9_999_999_999L) number*1000 else number
                return millis.takeIf { it in 946684800000L..4102444800000L }
            }
            val type = when(field("otherId","otherid")?.toIntOrNull() ?: item.otherId) {
                0 -> when(field("ifphoto","ifPhoto")){
                    "1","true" -> "拍照签到"
                    "0","false" -> "普通签到"
                    else -> "普通 / 拍照签到"
                }
                2 -> "二维码签到"
                3 -> "手势签到"
                4 -> "位置签到"
                5 -> "签到码"
                else -> "签到"
            }
            val count = attendedCount(data)
            return SignOverview(data.optString("name").trim().take(160).ifBlank{item.title},type,
                data.optInt("status"),time("startTime","starttime"),time("createtime"),time("endTime","endtime"),count)
        }

        /** Only the explicit attended-person field is supported by the observed student payloads.
         * A total, percentage, activity-list size, or missing value must never become a signed count.
         */
        internal fun attendedCount(data:JSONObject):Int? {
            val values=data.keys().asSequence().filter{it.equals("attendNum",ignoreCase=true)}
                .mapNotNull { key->
                    if(data.isNull(key))null else data.opt(key)?.let { value->
                        if(value is String && value.isBlank())null else value
                    }
                }.toList()
            if(values.isEmpty())return null
            val counts=values.map { value->
                if(value !is Number && value !is String)return null
                val encoded=value.toString().trim()
                if(encoded.length !in 1..40)return null
                val count=try { BigDecimal(encoded).intValueExact() }
                    catch(_:NumberFormatException){return null}
                    catch(_:ArithmeticException){return null}
                if(count<0)return null
                count
            }
            return counts.distinct().singleOrNull()
        }
    }
}
