package cn.classbell.app

import android.content.SharedPreferences
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class SignMode { NORMAL, PHOTO, CODE, LOCATION, QR, GESTURE, OFFICIAL }
data class SignPlan(val mode: SignMode, val message: String, val issueCode:String="", val repairNeeded:Boolean=mode==SignMode.OFFICIAL,val requiresLocation:Boolean=false,val requirements:SignRequirements=SignRequirements(),val overview:SignOverview?=null,val qrRefresh:QrRefreshInfo=QrRefreshInfo()) {
    init { DiagnosticLog.event("SignPlan", "mode=$mode issue=$issueCode repair=$repairNeeded reason=${DiagnosticLog.label(message)}") }
}
enum class SignResultCategory(val label:String) { SUCCESS("成功"), NOT_SUBMITTED("尚未提交"), FAILED("未成功"), UNKNOWN("结果待核实"), SERVER_LIMIT("服务端限制"), VERIFICATION("需要验证"), NEEDS_FIX("待修复"), ALREADY_ATTEMPTED("已有提交记录") }
enum class PhotoTransferState(val label:String) { NOT_SENT("未发送照片"), UNCONFIRMED("上传结果未确认"), CONFIRMED("照片上传已确认") }
class PhotoTransferException(val photoState:PhotoTransferState,cause:Exception):Exception("照片传输后流程未完成",cause)
data class SignOutcome(val successful: Boolean, val message: String, val category:SignResultCategory=if(successful)SignResultCategory.SUCCESS else SignResultCategory.FAILED,val retryAllowed:Boolean=false,val photoState:PhotoTransferState=PhotoTransferState.NOT_SENT) {
    init { DiagnosticLog.event("SignResult", "successful=$successful category=$category photo_state=$photoState reason=${DiagnosticLog.label(message)}", if(successful)"INFO" else "WARN") }
}

/** Each native entry explicitly enables the material it can collect; unknown requirements remain visible. */
object SignProtocol {
    private val ids = Regex("[0-9]{1,30}")
    fun validId(value: String) = ids.matches(value)
    fun detail(body: String, activity: SignActivity,allowCode:Boolean=false,allowLocation:Boolean=false,allowQr:Boolean=false,allowGesture:Boolean=false,
               listedInCourse: () -> Boolean = { false }): SignPlan {
        val root=ApiParser.json(body)
        if(root.optString("result") !in setOf("1","true"))throw ApiProblem("无法读取签到要求，请重试并导出日志。")
        val data=root.optJSONObject("data") ?: throw ApiProblem("签到详情格式未识别，待修复。")
        val requirements=SignRequirements.from(data)
        val overview=SignOverview.read(data,activity)
        val schema=data.keys().asSequence().filter{it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,48}"))}.sorted().take(100).joinToString(",")
        DiagnosticLog.event("SignSchema","${DiagnosticLog.activity(activity)} fields=$schema")
        fun field(vararg names:String):String? {
            val values=names.mapNotNull { name -> if(data.isNull(name))null else data.optString(name).trim().takeIf{it.isNotBlank()} }.distinct()
            return if(values.size>1)"CONFLICT" else values.singleOrNull()
        }
        fun flag(vararg names:String):String? = when(val value=field(*names)?.lowercase()) {
            "0","false"->"0";"1","true"->"1";else->value
        }
        fun blocked(code:String,message:String,repair:Boolean=true):SignPlan {
            DiagnosticLog.event("SignRequirement", "${DiagnosticLog.activity(activity)} rejected code=$code", "WARN")
            return SignPlan(SignMode.OFFICIAL,message,code,repair,requirements=requirements,overview=overview)
        }
        val id=field("id","activeId")
        val clazz=field("clazzid","clazzId","classId")
        val course=field("courseId","courseid")
        val type=field("otherId","otherid")
        val photo=flag("ifphoto","ifPhoto")
        val category=field("activeType","type")
        DiagnosticLog.event("SignRequirement", "${DiagnosticLog.activity(activity)} id_match=${id==activity.id} class_present=${clazz!=null} class_match=${clazz==activity.course.classId} course_present=${course!=null} course_match=${course==activity.course.id} type=${type ?: "missing"} photo=${photo ?: "missing"} status=${field("status") ?: "missing"}")
        if(id!=activity.id)return blocked("ACTIVITY_ID_MISMATCH","活动编号缺失或不一致，尚未提交。待修复：详情识别。")
        if(clazz!=null && clazz!=activity.course.classId)return blocked("CLASS_ID_MISMATCH","返回的班级与所选班级不一致，尚未提交。请刷新课程；仍不一致时导出日志。")
        if(course!=null && course!=activity.course.id)return blocked("COURSE_ID_MISMATCH","返回的课程与所选课程不一致，尚未提交。请刷新课程；仍不一致时导出日志。")
        if(category!="2")return blocked("ACTIVITY_TYPE_UNKNOWN","待修复：未能核对为签到活动。")
        if(field("status")!="1")return blocked("ACTIVITY_NOT_ACTIVE","签到未开始或已结束，尚未提交。",false)
        val now=System.currentTimeMillis()
        for(name in listOf("endTime","endtime")) {
            val raw=field(name)?.toLongOrNull() ?: continue
            val end=if(raw in 1_000_000_000L..9_999_999_999L)raw*1000 else raw
            if(end>0 && end<=now)return blocked("ACTIVITY_ENDED","签到已结束。",false)
        }
        if(type !in setOf("0","2","3","4","5"))return blocked("UNSUPPORTED_SIGN_TYPE",when(type){"2"->"待修复：扫码原生提交尚未接通，识别二维码不代表签到成功。";"3"->"待修复：手势签到尚未接通。";else->"待修复：签到类型无法识别。"})
        if(type=="2" && !allowQr)return blocked("QR_DISABLED","请打开扫码签到，核对二维码后提交。",false)
        if(type=="3" && !allowGesture)return blocked("GESTURE_DISABLED","请打开手势签到，输入老师公布的手势后提交。",false)
        if(type=="5" && !allowCode)return blocked("CODE_DISABLED","待修复：此入口未启用签到码提交。")
        val requirementFields=listOf("ifNeedVCode","showVCode","ifopenAddress","openCheckFaceFlag","openCheckWeChatFlag","openPreventCheatFlag","openSignOutFlag")
        requirementFields.forEach { name->
            DiagnosticLog.event("SignRequirement","${DiagnosticLog.activity(activity)} requirement=$name value=${when(flag(name)){null->"missing";"0"->"disabled";"1"->"enabled";else->"unrecognized"}}")
        }
        for(name in requirementFields) {
            val value=flag(name)
            val accepted=if(name=="ifopenAddress" && allowLocation && type in setOf("2","4"))setOf("0","1") else setOf("0")
            if(value==null)return blocked("MISSING_REQUIREMENT_$name","待修复：详情未完整返回签到要求，尚未提交；日志已记录缺少的信息。")
            if(name=="ifopenAddress" && value=="1" && !allowLocation)
                return blocked("LOCATION_DISABLED","位置签到不可用：本活动需要本次定位。")
            if(name=="openSignOutFlag" && value=="1")
                return blocked("EXTRA_REQUIREMENT_openSignOutFlag","老师启用了签退，离场时需另行确认。本活动暂不支持应用内提交。")
            if(value !in accepted)return blocked("EXTRA_REQUIREMENT_$name","待修复：${requirements.unsupported.joinToString("、").ifBlank{"附加签到要求"}}。尚未提交，可查看下方逐项要求。")
        }
        if(type=="4" && !allowLocation)return blocked("LOCATION_DISABLED","位置签到不可用。")
        val config=field("configJson").orEmpty()
        if(config !in setOf("","null","{}","[]") && !disabledClassSharing(config))return blocked("UNKNOWN_CONFIG","待修复：签到包含尚未识别的附加配置。")
        if(photo !in setOf("0","1"))return blocked("PHOTO_REQUIREMENT_UNKNOWN","待修复：无法确认是否需要照片。")
        if(photo=="1" && type!="0")return blocked("COMBINED_REQUIREMENTS","待修复：照片与其他签到方式的组合要求尚未接通。")
        // Missing metadata is corroborated against this account's authenticated class list.
        // Explicit mismatches above are never replaced with the requested course IDs.
        if(course==null || clazz==null) {
            if(!listedInCourse())return blocked("MEMBERSHIP_UNCONFIRMED","待修复：详情缺少课程或班级信息，当前账号的课程活动列表也未能核实。")
            DiagnosticLog.event("SignRequirement", "${DiagnosticLog.activity(activity)} membership_verified=authenticated_course_list")
        }
        return when {
            photo=="1"->SignPlan(SignMode.PHOTO,"已识别拍照签到：选择照片、核对后在本应用提交。")
            type=="2"->SignPlan(SignMode.QR,"已识别扫码签到：扫描当前二维码，核对账号后在本应用提交。",requiresLocation=flag("ifopenAddress")=="1",requirements=requirements,overview=overview,qrRefresh=QrRefreshInfo.read(data))
            type=="3"->SignPlan(SignMode.GESTURE,"已识别手势签到：按老师公布的顺序连接九宫格，在本应用提交。")
            type=="5"->SignPlan(SignMode.CODE,"已识别签到码：输入老师公布的签到码，在本应用提交。")
            type=="4"->SignPlan(SignMode.LOCATION,"已识别位置签到：核对本次位置，在本应用提交。")
            else->SignPlan(SignMode.NORMAL,"已识别普通签到，可在本应用提交。")
        }.copy(requirements=requirements,overview=overview)
    }
    private fun disabledClassSharing(raw: String): Boolean {
        val config = diagnosticRunCatching { JSONObject(raw) }.getOrNull() ?: return false
        val disabled = setOf("0", "false")
        return config.keys().asSequence().all { it in setOf("ifSendToOtherClass", "sendToOtherClassList", "onlyAttendForCurrentClass") } &&
            config.optString("ifSendToOtherClass") in disabled &&
            (!config.has("onlyAttendForCurrentClass") || config.optString("onlyAttendForCurrentClass") in disabled)
    }
    internal fun traceResponse(body:String,phase:String) {
        val value=body.trim()
        val data=if(value.startsWith("{"))try{JSONObject(value)}catch(_:Exception){null} else null
        val keys=data?.keys()?.asSequence()?.filter{it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,40}"))}?.sorted()?.take(40)?.joinToString(",").orEmpty()
        val flags=listOf("result","status","errorCode").mapNotNull { key->
            data?.optString(key)?.takeIf{it in setOf("true","false","success","0","1","2","3","400","401","403","404","500") || (key=="errorCode" && it.matches(Regex("-?[0-9]{1,6}")))}?.let{"$key=$it"}
        }.joinToString(" ")
        DiagnosticLog.event("SignResponse","phase=$phase chars=${value.length} shape=${when{value.startsWith("{")->"json_object";value.startsWith("<")->"html";else->"text"}} response_ref=${DiagnosticLog.reference(value)} fields=$keys $flags")
    }
    fun outcome(body: String): SignOutcome {
        val value=body.trim()
        traceResponse(value,"submit")
        if(value in setOf("success","success2"))return SignOutcome(true,if(value=="success2")"签到成功（迟到）" else "签到成功")
        if(value in setOf("您已签到过了","你已经签到过了","您已签到","已签到"))return SignOutcome(true,"学习通返回已签到")
        val structured=if(value.startsWith("{"))runCatching{JSONObject(value)}.getOrNull() else null
        val message=if(structured!=null && (structured.optString("status")=="false" || structured.optString("result") in setOf("false","0")))
            listOf("mes","msg","errorMsg").map{structured.optString(it)}.firstOrNull{it.isNotBlank()}.orEmpty() else value
        if(message.length<=256 && !message.contains('<') && Regex("^二维码[^<>]*(?:过期|失效|无效|已更新|已刷新)[^<>]*$").matches(message))
            return SignOutcome(false,"二维码已过期或已更新，请重新扫描当前码。",SignResultCategory.FAILED,retryAllowed=true)
        return when {
            value in setOf("二维码已过期","二维码已失效","二维码已更新","二维码无效","签到码错误","签到码不正确","手势不正确","手势错误") ->
                SignOutcome(false,if(value.startsWith("二维码"))"二维码未被接受，请重新扫描当前码后再提交。" else "签到码或手势未被接受，请修改后再提交。",retryAllowed=true)
            listOf("设备","人数","上限","一人").any(value::contains)->SignOutcome(false,"学习通返回设备或人数限制，本次未确认成功。",SignResultCategory.SERVER_LIMIT)
            listOf("validate","验证码","人机","人脸").any{value.contains(it,true)}->SignOutcome(false,"需要官方验证，本次未继续提交。",SignResultCategory.VERIFICATION)
            Regex("(?:在|使用)学习通(?:客户端|App|内|中)?(?:完成)?签到",RegexOption.IGNORE_CASE).containsMatchIn(value)->SignOutcome(false,"待修复：服务器要求官方客户端能力，本次未确认成功。",SignResultCategory.NEEDS_FIX)
            listOf("签到码错误","签到码不正确","二维码已过期","签到已结束").any(value::contains)->SignOutcome(false,"签到码无效、已过期或签到已结束，请重新核对。",SignResultCategory.FAILED)
            else->SignOutcome(false,"结果待核实：服务器回复尚未识别，不自动重复提交。",SignResultCategory.UNKNOWN)
        }
    }
    fun objectId(body: String): String {
        val root = ApiParser.json(body)
        val value = root.optString("objectId").ifBlank { root.optJSONObject("data")?.optString("objectId").orEmpty() }
        if (!Regex("[a-fA-F0-9]{32}").matches(value) || root.optString("result") in setOf("false", "0"))
            throw ApiProblem("学习通未确认照片上传成功，未提交签到。")
        return value
    }

    /** Read-only result observed in the official web response; never infer success from HTTP 200. */
    fun attendance(body:String,activity:SignActivity,uid:String):SignOutcome {
        traceResponse(body,"attendance_query")
        val root=ApiParser.json(body)
        fun unknown(message:String)=SignOutcome(false,message,SignResultCategory.UNKNOWN)
        if(root.optString("result") !in setOf("1","true"))return unknown("学习通尚未返回可核对的签到结果，请稍后刷新。")
        val data=root.optJSONObject("data") ?: return unknown("暂未取得签到记录；这不代表提交失败，请稍后刷新。")
        val activityMatches=data.optString("activeId")==activity.id
        val accountMatches=uid.isNotBlank() && data.optString("uid")==uid
        val status=data.optString("status")
        DiagnosticLog.event("SignAttendance","${DiagnosticLog.activity(activity)} activity_match=$activityMatches account_match=$accountMatches status=${status.takeIf{it.matches(Regex("[0-9]{1,3}"))} ?: "unrecognized"}")
        if(!activityMatches || !accountMatches)return unknown("返回记录与当前账号或活动不一致，请刷新账号状态后重试。")
        return when(status) {
            "1"->{
                val confirmedAt=data.optString("createtime").toLongOrNull()
                    ?.takeIf{it in 946684800000L..4102444800000L}
                val time=confirmedAt?.let{"\n签到时间：${HomeworkDeadline.format(it)}"}.orEmpty()
                SignOutcome(true,"学习通已确认当前账号签到成功。$time")
            }
            "0"->SignOutcome(false,"学习通当前记录为未签到；查询不会重新提交。",SignResultCategory.NOT_SUBMITTED)
            else->unknown("学习通返回了其他签到状态，请稍后核对；未自动重新提交。")
        }
    }
}

