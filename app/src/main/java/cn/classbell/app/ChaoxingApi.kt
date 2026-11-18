package cn.classbell.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChaoxingProtocol {
    const val LOGIN_PAGE = "https://passport2.chaoxing.com/login?newversion=true&refer=https%3A%2F%2Fi.chaoxing.com"
    val hosts = setOf("passport2.chaoxing.com", "mooc1-api.chaoxing.com", "mobilelearn.chaoxing.com", "i.chaoxing.com", "sso.chaoxing.com", "pan-yz.chaoxing.com")
    fun allowed(url: HttpUrl) = url.isHttps && url.port == 443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
    fun activityListUrl(course: Course, cookies: List<Cookie>): HttpUrl {
        val fid = cookies.firstOrNull { it.name == "fid" && SignProtocol.validId(it.value) }?.value ?: "0"
        return "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist".toHttpUrl().newBuilder()
            .addQueryParameter("fid", fid).addQueryParameter("courseId", course.id).addQueryParameter("classId", course.classId)
            .addQueryParameter("showNotStartedActive", "0").addQueryParameter("_", System.currentTimeMillis().toString()).build()
    }
    fun loginRedirect(from: HttpUrl, location: String): HttpUrl? = location.takeIf { it.isNotBlank() }?.let(from::resolve)?.takeIf {
        it.isHttps && it.host == "passport2.chaoxing.com" && it.encodedPath in setOf("/login", "/mlogin") &&
            it.username.isEmpty() && it.password.isEmpty()
    }

    fun checkLoginResult(root: JSONObject) {
        if (root.optBoolean("containTwoFactorLogin"))
            throw ApiProblem("登录需要二次验证，请使用官方网页登录完成验证。")
        if (root.optBoolean("status")) return
        // The official fanya login script displays msg2. Keep raw replies out of diagnostics.
        val message = listOf("msg2", "msg", "errorMsg", "message").joinToString(" ") { root.optString(it) }
        val reason = when {
            listOf("验证码", "验证", "安全", "风险", "频繁").any { message.contains(it) } ->
                "登录需要验证码或安全验证，请使用官方网页登录完成验证。"
            listOf("密码", "账号", "帐号", "账户", "用户").any { message.contains(it) } ->
                "学习通未接受账号或密码。请核对输入；学号登录请在官方网页登录中选择学校 / 机构。"
            else -> "学习通未接受此次账号登录，请使用官方网页登录；学号登录请在官方页面选择学校 / 机构。"
        }
        throw ApiProblem(reason)
    }
    fun encryptLogin(value: String): String {
        // Matches the currently served official login page's transport encoding.
        val key = "u2oh6Vu^HWe4_AES".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
}

class SessionCookies(private val vault: SecureStore,private val storageName:String?="cookies") : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    init {
        val saved = diagnosticRunCatching { JSONArray(storageName?.let{vault.read(it)} ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            diagnosticRunCatching { Cookie.parse(item.getString("origin").toHttpUrl(), item.getString("cookie")) }
                .getOrNull()?.let { if (valid(it)) cookies.add(it) }
        }
    }
    private fun valid(cookie: Cookie) = cookie.domain == "chaoxing.com" || cookie.domain.endsWith(".chaoxing.com")
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (!ChaoxingProtocol.allowed(url)) emptyList() else cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    @Synchronized override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        if (!ChaoxingProtocol.allowed(url)) return
        for (cookie in received.filter { valid(it) }) {
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
        }
        persist()
    }
    private fun persist() {
        val name=storageName ?: return
        val array = JSONArray()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        for (cookie in cookies) array.put(JSONObject().put("origin", "https://${cookie.domain}/").put("cookie", cookie.toString()))
        vault.write(name, array.toString())
    }
    @Synchronized fun clear() { cookies.clear(); persist() }
    @Synchronized fun replaceWith(values:List<Cookie>) {cookies.clear();cookies.addAll(values.filter{valid(it) && it.expiresAt>System.currentTimeMillis()});persist()}
    @Synchronized fun userId(): String? = cookies.firstOrNull { it.name in setOf("_uid", "UID", "uid") }?.value
    @Synchronized fun snapshot(): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() }.toList()
    @Synchronized fun export(url: String): String = loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
    @Synchronized fun importWebCookie(url: String, header: String) {
        val origin = url.toHttpUrl()
        val imported = header.split(';').mapNotNull { pair ->
            Cookie.parse(origin, "${pair.trim()}; Domain=.chaoxing.com; Path=/; Secure")
        }
        saveFromResponse(origin, imported)
    }
}

class ChaoxingApi(private val cookies: SessionCookies) {
    @Volatile var verifiedProfile:AccountProfile?=null
        private set
    private val client = OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("ChaoxingApi")).cookieJar(cookies)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            if (!ChaoxingProtocol.allowed(request.url)) throw java.io.IOException("请求地址不受支持")
            chain.proceed(request)
        }.build()

    fun cancelRequests() { client.dispatcher.cancelAll() }

    private fun request(builder: Request.Builder, loginPage: Boolean = false): String {
        var request = builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*").build()
        repeat(5) {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw LoginRequired()
                if (response.code in 300..399) {
                    val location = response.header("Location").orEmpty()
                    if (loginPage) {
                        val target = ChaoxingProtocol.loginRedirect(request.url, location)
                            ?: throw ApiProblem("登录页跳转到不支持的地址，请使用官方网页登录。")
                        request = request.newBuilder().url(target).get().build()
                        return@repeat
                    }
                    if (location.contains("passport2.") || location.contains("/login")) throw LoginRequired()
                    throw ApiProblem("接口发生跳转，请尝试官方网页登录")
                }
                if (response.code == 429) throw ApiProblem("查询频率受限，已降低查询频率")
                if (!response.isSuccessful) throw ApiProblem("学习通暂时无法访问（HTTP ${response.code}）")
                val body = response.body ?: throw ApiProblem("学习通返回了空响应")
                val source = body.source()
                if (source.request(4L * 1024 * 1024 + 1)) throw ApiProblem("接口响应过大，请稍后重试")
                return body.string()
            }
        }
        throw ApiProblem("登录页跳转次数过多，请使用官方网页登录。")
    }

    fun login(account: String, password: String): List<Course> {
        DiagnosticLog.event("Login", "password_login_begin account=${DiagnosticLog.reference(account)}")
        cookies.clear()
        request(Request.Builder().url(ChaoxingProtocol.LOGIN_PAGE), loginPage = true)
        val form = FormBody.Builder().add("fid", "-1")
            .add("uname", ChaoxingProtocol.encryptLogin(account))
            .add("password", ChaoxingProtocol.encryptLogin(password))
            .add("refer", "https%3A%2F%2Fi.chaoxing.com")
            .add("t", "true").add("forbidotherlogin", "0").add("validate", "")
            .add("doubleFactorLogin", "0").add("independentId", "0").add("independentNameId", "0").build()
        val root = ApiParser.json(request(Request.Builder().url("https://passport2.chaoxing.com/fanyalogin")
            .header("Referer", ChaoxingProtocol.LOGIN_PAGE).post(form)))
        DiagnosticLog.event("Login", "reply_received accepted=${root.optBoolean("status")} second_factor=${root.optBoolean("containTwoFactorLogin")}")
        ChaoxingProtocol.checkLoginResult(root)
        return try { courses() } catch (diagnosticError: LoginRequired) { DiagnosticLog.error("ChaoxingApi", diagnosticError);
            throw ApiProblem("账号验证返回成功，但课程接口尚未收到有效登录会话。请使用官方网页登录后再读取课程。")
        }
    }

    fun profile():AccountProfile = DiagnosticLog.operation("AccountProfile", "account=${DiagnosticLog.reference(cookies.userId().orEmpty())}") { AccountProfile.parse(request(Request.Builder().url("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")),cookies.userId().orEmpty()).also{verifiedProfile=it} }
    fun courses(): List<Course> {
        profile()
        return DiagnosticLog.operation("Courses", "account=${DiagnosticLog.reference(cookies.userId().orEmpty())}") { ApiParser.courses(request(Request.Builder().url("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1"))) }.also { DiagnosticLog.event("Courses", "loaded count=${it.size}");it.forEach{course->DiagnosticLog.event("Course", "${DiagnosticLog.course(course)} loaded=true")} }
    }
    fun activityFeed(course:Course):List<CourseActivity> {
        val uid=cookies.userId().orEmpty()
        if(!SignProtocol.validId(uid))throw LoginRequired()
        val url=ChaoxingProtocol.activityListUrl(course,cookies.snapshot())
        return DiagnosticLog.operation("CourseActivities", DiagnosticLog.course(course)) { CourseActivity.parse(request(Request.Builder().url(url)),course) }.also { DiagnosticLog.event("CourseActivities", "${DiagnosticLog.course(course)} count=${it.size}");it.forEach{row->DiagnosticLog.event("CourseActivity", "${DiagnosticLog.activity(row.sign)} type=${row.activeType} status=${row.status}")} }
    }

    fun activities(course: Course): List<SignActivity> {
        val url = ChaoxingProtocol.activityListUrl(course, cookies.snapshot())
        return DiagnosticLog.operation("ActivityPolling", DiagnosticLog.course(course)) { ApiParser.activities(request(Request.Builder().url(url)), course) }.also { DiagnosticLog.event("ActivityPolling", "${DiagnosticLog.course(course)} count=${it.size}");it.forEach{item->DiagnosticLog.event("Activity", "${DiagnosticLog.activity(item)} kind=${item.kind} type=${item.otherId}")} }
    }
}
