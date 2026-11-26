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

interface SignSource {
    fun activities(course: Course): List<SignActivity>
    fun activityFeed(course: Course): List<CourseActivity> = emptyList()
    fun overview(activity: SignActivity): SignOverview? = null
    fun plan(activity: SignActivity): SignPlan
    fun attendance(activity:SignActivity):SignOutcome = throw ApiProblem("当前来源未接通签到结果查询。")
    fun submit(activity: SignActivity, photo: File? = null, beforeCommit: () -> Unit = {}): SignOutcome
    fun submitCode(activity:SignActivity,code:String,beforeCommit:()->Unit={}):SignOutcome = throw ApiProblem("当前签到码入口不可用，请在学习通完成。")
    fun submitLocation(activity:SignActivity,fix:LocationFix,beforeCommit:()->Unit={}):SignOutcome = throw ApiProblem("请在学习通完成位置签到。")
    fun submitManualLocation(activity:SignActivity,point:ManualLocation,beforeCommit:()->Unit={}):SignOutcome = throw ApiProblem("手动位置提交暂不可用，请在学习通完成。")
    fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?=null,beforeCommit:()->Unit={}):SignOutcome = throw ApiProblem("扫码提交暂不可用，待修复。")
    fun submitGesture(activity:SignActivity,path:String,beforeCommit:()->Unit={}):SignOutcome = throw ApiProblem("手势提交暂不可用，待修复。")
    fun prepareQr(activity:SignActivity):PreparedQr {
        val checked=plan(activity)
        if(checked.mode!=SignMode.QR)throw ApiProblem(checked.message)
        return PreparedQr(this,activity,checked){qr,fix,before->submitQr(activity,qr,fix,before)}
    }
    fun cancel()
}

/** Fixed cookie snapshot prevents an old operation from borrowing a newly logged-in account's session. */
class SigningApi(private val snapshot: List<Cookie>, private val guard: () -> Unit,
                 private val allowCode:Boolean=false,private val allowLocation:Boolean=false,private val allowQr:Boolean=false,private val allowGesture:Boolean=false,private val exchange: ((Request) -> String)? = null) : SignSource {
    constructor(cookies: SessionCookies, guard: () -> Unit):this(cookies.snapshot(),guard)
    @Volatile private var cancelled=false
    private val uid = snapshot.firstOrNull { it.name in setOf("_uid", "UID", "uid") }?.value.orEmpty()
    private val client = OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("Signing")).cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl) = if (ChaoxingProtocol.allowed(url)) snapshot.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } else emptyList()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
    }).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    override fun cancel() {
        if(cancelled)return
        cancelled=true
        closeNetworkAsync { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
    private fun get(path: String, values: Map<String, String> = emptyMap(),timeoutMillis:Long?=null): String {
        val url = path.toHttpUrl().newBuilder().apply { values.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        return request(Request.Builder().url(url).get(),timeoutMillis)
    }
    private fun request(builder: Request.Builder,timeoutMillis:Long?=null): String {
        if(cancelled) throw ApiProblem("签到操作已取消。")
        guard()
        val request = builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36").build()
        if (!ChaoxingProtocol.allowed(request.url)) throw IOException("不支持的签到请求地址")
        exchange?.let { return it(request) }
        val call=client.newCall(request)
        timeoutMillis?.let{call.timeout().timeout(it,TimeUnit.MILLISECONDS)}
        call.execute().use { response ->
            if (response.code == 401 || response.code in 300..399) throw LoginRequired()
            if (!response.isSuccessful) throw ApiProblem("学习通请求未完成（HTTP ${response.code}）；未自动重试。")
            val body = response.body ?: throw ApiProblem("学习通返回空响应，需到官方核对。")
            if (body.source().request(4L*1024*1024+1)) throw ApiProblem("签到响应过大，已停止。")
            return body.string()
        }
    }
    override fun activities(course: Course): List<SignActivity> {
        val url = ChaoxingProtocol.activityListUrl(course, snapshot)
        return DiagnosticLog.operation("SignActivities",DiagnosticLog.course(course)){ApiParser.activities(request(Request.Builder().url(url).get()), course)}.also{DiagnosticLog.event("SignActivities","${DiagnosticLog.course(course)} count=${it.size}")}
    }
    override fun activityFeed(course:Course): List<CourseActivity> {
        val url=ChaoxingProtocol.activityListUrl(course,snapshot)
        return DiagnosticLog.operation("SignHistory",DiagnosticLog.course(course)){
            CourseActivity.parse(request(Request.Builder().url(url)),course)
        }
    }
    private fun details(activity: SignActivity): String {
        if (activity.kind != ReminderKind.SIGN_IN || !listOf(activity.id, activity.course.id, activity.course.classId).all(SignProtocol::validId))
            throw ApiProblem("不是有效的课程签到。")
        return get("https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo", mapOf("activeId" to activity.id))
    }
    override fun overview(activity:SignActivity):SignOverview? {
        val root=ApiParser.json(details(activity))
        if(root.optString("result") !in setOf("1","true"))return null
        return root.optJSONObject("data")?.let{SignOverview.read(it,activity)}
    }
    override fun attendance(activity:SignActivity):SignOutcome {
        if(activity.kind!=ReminderKind.SIGN_IN || !SignProtocol.validId(activity.id) || !SignProtocol.validId(uid))
            throw ApiProblem("签到或账号身份无效，无法查询结果。")
        return DiagnosticLog.operation("SignAttendance",DiagnosticLog.activity(activity)) {
            val body=get("https://mobilelearn.chaoxing.com/v2/apis/sign/getAttendInfo",mapOf("activeId" to activity.id,"moreClassAttendEnc" to ""),timeoutMillis=2_500)
            SignProtocol.attendance(body,activity,uid)
        }
    }
    override fun plan(activity: SignActivity): SignPlan {
        DiagnosticLog.event("SignPlan", "${DiagnosticLog.activity(activity)} checking_requirements=true")
        return SignProtocol.detail(details(activity), activity, allowCode, allowLocation,allowQr,allowGesture) {
            activities(activity.course).any { it.id == activity.id && it.kind==ReminderKind.SIGN_IN }
        }
    }
    override fun submitCode(activity:SignActivity,code:String,beforeCommit:()->Unit):SignOutcome {
        if(!Regex("[0-9]{4,16}").matches(code))throw ApiProblem("请填写老师公布的签到码。")
        return submitMaterial(activity,SignMode.CODE,code=code,beforeCommit=beforeCommit)
    }
    override fun submitGesture(activity:SignActivity,path:String,beforeCommit:()->Unit):SignOutcome {
        GesturePath.requireValid(path)
        return submitMaterial(activity,SignMode.GESTURE,code=path,beforeCommit=beforeCommit)
    }
    override fun prepareQr(activity:SignActivity):PreparedQr {
        guard()
        if(activities(activity.course).none{it.id==activity.id && it.kind==ReminderKind.SIGN_IN})throw ApiProblem("签到已不在进行中，请刷新活动。")
        val checked=plan(activity)
        if(checked.mode!=SignMode.QR)throw ApiProblem(checked.message)
        val user=ApiParser.json(get("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")).optJSONObject("msg") ?: throw LoginRequired()
        if(!SignProtocol.validId(uid) || uid !in setOf(user.optString("uid"),user.optString("puid")) || user.optString("name").isBlank())throw LoginRequired()
        AccountAccess.check(user.optString("name"))
        val fid=snapshot.firstOrNull{it.name=="fid"}?.value.orEmpty().ifBlank{user.optString("fid")}
        if(!SignProtocol.validId(fid))throw ApiProblem("请重新登录此账号以取得学校编号。")
        val page=get("https://mobilelearn.chaoxing.com/newsign/preSign",mapOf("activePrimaryId" to activity.id,"courseId" to activity.course.id,
            "classId" to activity.course.classId,"uid" to uid,"general" to "1","sys" to "1","ls" to "1","appType" to "15","ut" to "s"))
        if(page.contains("用户登录") || page.contains("passport2.chaoxing.com/login"))throw LoginRequired()
        if(listOf("请先完成验证","请完成安全验证","请进行人脸识别").any(page::contains))throw ApiProblem("此活动需要完成官方验证。")
        // This request also warms the exact server connection used by the final submission.
        val finalPlan=plan(activity)
        if(finalPlan.mode!=SignMode.QR || finalPlan.requiresLocation!=checked.requiresLocation)throw ApiProblem("签到要求已变化，请重新准备。")
        DiagnosticLog.event("QrTiming","prepared=true refresh_seconds=${finalPlan.qrRefresh.seconds ?: -1}")
        return PreparedQr(this,activity,finalPlan){qr,fix,before->
            guard();qr.requireFor(activity);qr.requireFresh()
            val fields=linkedMapOf("activeId" to activity.id,"uid" to uid,"name" to user.optString("name"),"fid" to fid,
                "clientip" to "","useragent" to "","latitude" to "-1","longitude" to "-1","appType" to "15","ifTiJiao" to "1","enc" to qr.enc)
            if(finalPlan.requiresLocation) {
                val current=fix ?: throw ApiProblem("请先更新本次手机定位。")
                current.requireCurrent()
                fields["location"]=JSONObject().put("result",1).put("latitude",current.latitude).put("longitude",current.longitude)
                    .put("address","当前位置（${current.latitude}, ${current.longitude}）").toString()
            }
            if(finalPlan.qrRefresh.enabled==true && qr.code!=null) {
                qr.requireFresh()
                val rcode="SIGNIN:aid=${activity.id}&source=15&Code=${qr.code}&enc=${qr.enc}"
                val boundPage="https://mobilelearn.chaoxing.com/newsign/preSign".toHttpUrl().newBuilder()
                    .addQueryParameter("activePrimaryId",activity.id).addQueryParameter("courseId",activity.course.id)
                    .addQueryParameter("classId",activity.course.classId).addQueryParameter("uid",uid)
                    .addQueryParameter("general","1").addQueryParameter("sys","1").addQueryParameter("ls","1")
                    .addQueryParameter("appType","15").addQueryParameter("ut","s").addQueryParameter("rcode",rcode).build()
                val remaining=(4_500-(android.os.SystemClock.elapsedRealtime()-qr.capturedAt)).coerceIn(1,4_500)
                val checkedPage=request(Request.Builder().url(boundPage).get(),remaining)
                if(checkedPage.contains("用户登录") || checkedPage.contains("passport2.chaoxing.com/login"))throw LoginRequired()
                if(listOf("请先完成验证","请完成安全验证","请进行人脸识别").any(checkedPage::contains))throw ApiProblem("当前二维码需要完成官方验证。")
            }
            before();guard();qr.requireFresh()
            val url="https://mobilelearn.chaoxing.com/pptSign/stuSignajax".toHttpUrl().newBuilder()
                .apply{fields.forEach{(k,v)->addQueryParameter(k,v)}}.build()
            val budget=(4_500-(android.os.SystemClock.elapsedRealtime()-qr.capturedAt)).coerceIn(1,4_500)
            DiagnosticLog.event("QrTiming","dispatch_ms=${android.os.SystemClock.elapsedRealtime()-qr.capturedAt} budget_ms=$budget")
            val outcome=SignProtocol.outcome(request(Request.Builder().url(url).tag(QrDispatchTrace::class.java,QrDispatchTrace(qr.capturedAt)).get(),budget))
            DiagnosticLog.event("QrTiming","result_ms=${android.os.SystemClock.elapsedRealtime()-qr.capturedAt} confirmed=${outcome.successful} category=${outcome.category}")
            outcome
        }
    }
    override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
        qr.requireFor(activity)
        return submitMaterial(activity,SignMode.QR,qr=qr,fix=fix,beforeCommit=beforeCommit)
    }
    private fun submitMaterial(activity:SignActivity,mode:SignMode,code:String="",qr:SignQr?=null,fix:LocationFix?=null,beforeCommit:()->Unit):SignOutcome {
        DiagnosticLog.event("SignSubmit","${DiagnosticLog.activity(activity)} mode=$mode material_present=true retry=false")
        fun revalidate():SignPlan {
            guard();qr?.requireFor(activity)
            if(activities(activity.course).none{it.id==activity.id && it.kind==ReminderKind.SIGN_IN})throw ApiProblem("签到已不在进行中，尚未提交。")
            val current=plan(activity)
            if(current.mode!=mode)throw ApiProblem("签到要求已变化，请重新核对。${current.message}")
            if(current.requiresLocation)(fix ?: throw ApiProblem("此二维码还要求位置，请先获取本次手机定位。")).requireCurrent()
            return current
        }
        revalidate()
        val user=ApiParser.json(get("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")).optJSONObject("msg") ?: throw LoginRequired()
        if(!SignProtocol.validId(uid) || uid !in setOf(user.optString("uid"),user.optString("puid")) || user.optString("name").isBlank())throw LoginRequired()
        AccountAccess.check(user.optString("name"))
        val fid=snapshot.firstOrNull{it.name=="fid"}?.value.orEmpty().ifBlank{user.optString("fid")}
        if(!SignProtocol.validId(fid))throw ApiProblem("未取得此账号的学校编号，请重新登录。")
        val page=get("https://mobilelearn.chaoxing.com/newsign/preSign",mapOf("activePrimaryId" to activity.id,"courseId" to activity.course.id,
            "classId" to activity.course.classId,"uid" to uid,"general" to "1","sys" to "1","ls" to "1","appType" to "15","ut" to "s"))
        if(page.contains("用户登录") || page.contains("passport2.chaoxing.com/login"))throw LoginRequired()
        if(listOf("请先完成验证","请完成安全验证","请进行人脸识别").any(page::contains))throw ApiProblem("此活动需要额外验证，尚未提交。")
        if(mode in setOf(SignMode.CODE,SignMode.GESTURE)) {
            val checkedBody=get("https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode",mapOf("activeId" to activity.id,"signCode" to code))
            SignProtocol.traceResponse(checkedBody,"check_code")
            val checked=ApiParser.json(checkedBody)
            if(checked.optString("result") !in setOf("1","true"))return SignOutcome(false,"签到码或手势未通过核对，尚未提交；请检查输入。",SignResultCategory.NOT_SUBMITTED)
        }
        val finalPlan=revalidate()
        val fields=linkedMapOf("activeId" to activity.id,"uid" to uid,"name" to user.optString("name"),"fid" to fid,
            "clientip" to "","useragent" to "","latitude" to "-1","longitude" to "-1","appType" to "15","ifTiJiao" to "1")
        if(qr!=null)fields["enc"]=qr.enc else fields["signCode"]=code
        if(finalPlan.requiresLocation) {
            val location=fix ?: throw ApiProblem("请更新本次手机定位。")
            location.requireCurrent()
            fields["location"]=JSONObject().put("result",1).put("latitude",location.latitude).put("longitude",location.longitude)
                .put("address","当前位置（${location.latitude}, ${location.longitude}）").toString()
        }
        beforeCommit();guard()
        return SignProtocol.outcome(get("https://mobilelearn.chaoxing.com/pptSign/stuSignajax",fields))
    }
    override fun submitLocation(activity:SignActivity,fix:LocationFix,beforeCommit:()->Unit):SignOutcome {
        return submitCoordinates(activity,fix.latitude,fix.longitude,"当前位置（${fix.latitude}, ${fix.longitude}）",{fix.requireCurrent()},beforeCommit)
    }
    override fun submitManualLocation(activity:SignActivity,point:ManualLocation,beforeCommit:()->Unit):SignOutcome {
        return submitCoordinates(activity,point.latitude,point.longitude,point.address.ifBlank{"手动选点（${point.latitude}, ${point.longitude}）"},{point.requireValid()},beforeCommit)
    }
    private fun submitCoordinates(activity:SignActivity,latitude:Double,longitude:Double,address:String,check:()->Unit,beforeCommit:()->Unit):SignOutcome {
        DiagnosticLog.event("SignSubmit", "${DiagnosticLog.activity(activity)} mode=location revalidate=true retry=false")
        fun revalidate(){
            check()
            if(activities(activity.course).none{it.id==activity.id && it.kind==ReminderKind.SIGN_IN} || plan(activity).mode!=SignMode.LOCATION)throw ApiProblem("位置签到要求已变化，请重新核对。")
        }
        revalidate()
        val user=ApiParser.json(get("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")).optJSONObject("msg") ?: throw LoginRequired()
        if(!SignProtocol.validId(uid) || uid !in setOf(user.optString("uid"),user.optString("puid")) || user.optString("name").isBlank())throw LoginRequired()
        AccountAccess.check(user.optString("name"))
        val page=get("https://mobilelearn.chaoxing.com/newsign/preSign",mapOf("activePrimaryId" to activity.id,"courseId" to activity.course.id,"classId" to activity.course.classId,"uid" to uid,"general" to "1","sys" to "1","ls" to "1","appType" to "15","ut" to "s"))
        if(listOf("请先完成验证","请完成安全验证","请进行人脸识别","用户登录").any(page::contains))throw ApiProblem("请在学习通完成官方验证。")
        revalidate();beforeCommit();guard()
        return SignProtocol.outcome(get("https://mobilelearn.chaoxing.com/pptSign/stuSignajax",mapOf("activeId" to activity.id,"uid" to uid,"name" to user.optString("name"),
            "fid" to user.optString("fid"),"latitude" to latitude.toString(),"longitude" to longitude.toString(),"address" to address,"ifTiJiao" to "1","appType" to "15")))
    }
    override fun submit(activity: SignActivity, photo: File?, beforeCommit: () -> Unit): SignOutcome {
        var photoState=PhotoTransferState.NOT_SENT
        try {
        DiagnosticLog.event("SignSubmit", "${DiagnosticLog.activity(activity)} mode=${if(photo==null)"normal" else "photo"} revalidate=true retry=false")
        fun revalidate() {
            if (activities(activity.course).none { it.id == activity.id && it.kind == ReminderKind.SIGN_IN }) throw ApiProblem("签到已不在进行中，未提交。")
            val current = plan(activity)
            if (current.mode != if (photo == null) SignMode.NORMAL else SignMode.PHOTO) throw ApiProblem(current.message)
        }
        revalidate()
        val user = ApiParser.json(get("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")).optJSONObject("msg") ?: throw LoginRequired()
        if (!SignProtocol.validId(uid) || uid !in setOf(user.optString("uid"), user.optString("puid"))) throw LoginRequired()
        val name = user.optString("name")
        AccountAccess.check(name)
        val fid = snapshot.firstOrNull { it.name == "fid" }?.value.orEmpty().ifBlank { user.optString("fid") }
        if (name.isBlank() || !SignProtocol.validId(fid)) throw ApiProblem("无法确认本人身份或学校，请使用学习通。")
        // Normal official page preparation only. Never solve a captcha or synthesize verification credentials.
        val page = get("https://mobilelearn.chaoxing.com/newsign/preSign", mapOf("activePrimaryId" to activity.id,
            "courseId" to activity.course.id, "classId" to activity.course.classId, "uid" to uid, "general" to "1", "sys" to "1", "ls" to "1", "appType" to "15", "ut" to "s"))
        if (page.contains("用户登录") || page.contains("passport2.chaoxing.com/login")) throw LoginRequired()
        if (listOf("请先完成验证", "请完成安全验证", "请进行人脸识别").any(page::contains)) throw ApiProblem("需要官方验证，未提交签到。")
        var objectId: String? = null
        if (photo != null) {
            if (!photo.isFile || photo.length() !in 1..4L*1024*1024) throw ApiProblem("照片不可用，请重新选图。")
            val token = ApiParser.json(get("https://pan-yz.chaoxing.com/api/token/uservalid")).optString("_token")
            if (token.isBlank() || token.length > 4096) throw ApiProblem("未取得学习通照片上传凭证。")
            guard()
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("puid", uid)
                .addFormDataPart("file", "sign-photo.jpg", photo.asRequestBody("image/jpeg".toMediaType())).build()
            val target = "https://pan-yz.chaoxing.com/upload".toHttpUrl().newBuilder()
                .addQueryParameter("_from", "mobilelearn").addQueryParameter("_token", token).build()
            DiagnosticLog.event("SignPhoto", "${DiagnosticLog.activity(activity)} upload_begin bytes=${photo.length()}")
            photoState=PhotoTransferState.UNCONFIRMED
            objectId = SignProtocol.objectId(request(Request.Builder().url(target).post(form)))
            photoState=PhotoTransferState.CONFIRMED
            DiagnosticLog.event("SignPhoto", "${DiagnosticLog.activity(activity)} upload_confirmed=true")
            revalidate() // The activity may have ended while the user was uploading.
        }
        revalidate()
        beforeCommit(); guard()
        val fields = linkedMapOf("activeId" to activity.id, "uid" to uid, "name" to name, "fid" to fid,
            "clientip" to "", "useragent" to "", "latitude" to "-1", "longitude" to "-1", "appType" to "15")
        objectId?.let { fields["objectId"] = it }
        return SignProtocol.outcome(get("https://mobilelearn.chaoxing.com/pptSign/stuSignajax", fields)).copy(photoState=photoState)
        } catch(error:Exception) {
            if(photoState!=PhotoTransferState.NOT_SENT)throw PhotoTransferException(photoState,error)
            throw error
        }
    }
}

