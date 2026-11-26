package cn.classbell.app

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

data class HomeworkDetailPage(val html: String, val url: String, val expectedQuestions: Int? = null)
class HomeworkStartRequired : Exception("这份作业需要先进入答题才能读取题目；进入可能创建答题记录。")

/** Only navigation/read endpoints; this policy is separate from the background deadline scan. */
object HomeworkDetailPolicy {
    private val hosts = setOf(
        "mooc1-api.chaoxing.com", "mooc1.chaoxing.com", "mooc1-2.chaoxing.com", "mooc2-ans.chaoxing.com"
    )
    private val paths = setOf(
        "/mooc-ans/android/mtaskmsgspecial",
        "/mooc-ans/mooc2/work/task",
        "/mooc-ans/work/phone/task",
        "/mooc-ans/work/phone/task-work",
        "/mooc-ans/work/phone/work",
        "/mooc-ans/work/phone/preview",
        "/mooc-ans/work/phone/prework",
        "/mooc-ans/work/phone/view",
        "/mooc-ans/work/phone/detail",
        "/mooc-ans/work/phone/workdetail",
        "/mooc-ans/work/phone/studentworkdetail",
        "/mooc-ans/work/phone/taskmsg",
        "/mooc-ans/work/phone/selectworkquestion",
        "/mooc-ans/work/phone/selectworkquestionyipiyue"
    )
    private val writes = setOf("submit", "save", "delete", "upload", "answer", "start", "dohomework")
    private val operationKeys = setOf("action", "method", "operation", "command", "func")

    fun allowed(value: String): Boolean = value.takeIf { it.length <= 8192 }
        ?.toHttpUrlOrNull()?.let { allowed(it) } ?: false

    internal fun allowed(url: HttpUrl, allowBegin: Boolean = false): Boolean {
        if (url.toString().length > 8192 || !url.isHttps || url.port != 443 || url.host !in hosts || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null ||
            (url.encodedPath.lowercase() !in paths && !(allowBegin && url.encodedPath.equals("/mooc-ans/work/phone/doHomeWork", true)))) return false
        return url.queryParameterNames.none { name ->
            val key = name.lowercase()
            key in writes || key in operationKeys && url.queryParameterValues(name).any { value ->
                value?.lowercase()?.let { action -> writes.any { action.contains(it) } } == true
            }
        }
    }

    internal fun login(url: HttpUrl): Boolean =
        url.host in setOf("passport2.chaoxing.com", "passport2-api.chaoxing.com", "sso.chaoxing.com") ||
            url.pathSegments.any { it.equals("login", true) || it.equals("mlogin", true) }
}

/**
 * Created for a user-initiated read with one account's immutable cookie snapshot.
 * Entering an unstarted work requires explicit begin; scripts/forms, assets and cookies are not executed or persisted.
 */
class HomeworkDetailSource(
    cookies: List<Cookie>,
    private val guard: () -> Unit,
    transport: OkHttpClient? = null
) {
    constructor(cookies: SessionCookies, guard: () -> Unit, transport: OkHttpClient? = null) :
        this(cookies.snapshot(), guard, transport)

    private val snapshot = cookies.toList()
    private val calls = mutableSetOf<Call>()
    @Volatile private var cancelled = false
    private val client = (transport?.newBuilder() ?: OkHttpClient.Builder())
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    fun cancel() {
        val pending = synchronized(calls) {
            cancelled = true
            calls.toList()
        }
        // The injected client may share its dispatcher with other features.
        pending.forEach(Call::cancel)
    }

    fun load(item: HomeworkItem, begin: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): HomeworkDetailPage {
        val first=readPage(item,begin)
        val cursor=HomeworkPageCursor.read(first) ?: return first
        if(cursor.total==1)return first.copy(expectedQuestions=1)
        val pages=mutableMapOf<Int,String>()
        val ids=mutableSetOf<String>()
        var bytes=0L
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
        fun accept(page:HomeworkDetailPage,index:Int) {
            checkActive()
            val next=HomeworkPageCursor.read(page) ?: throw ApiProblem("作业分页结构发生变化，整份题目未读取完成。")
            if(next.total!=cursor.total || next.index!=index || next.workAnswerId!=cursor.workAnswerId)
                throw ApiProblem("作业页码或答题记录不一致，整份题目未读取完成。")
            if(!ids.add(next.questionId))throw ApiProblem("学习通重复返回同一道题，整份题目未读取完成。")
            bytes+=page.html.toByteArray(Charsets.UTF_8).size
            if(bytes>40L*1024*1024)throw ApiProblem("作业分页内容过大，整份题目未读取完成。")
            pages[index]=next.fragment
            progress(pages.size,cursor.total)
        }
        accept(first,cursor.index)
        for(index in 0 until cursor.total) {
            if(index in pages)continue
            checkActive()
            if(System.nanoTime()>deadline)throw ApiProblem("读取整份作业超时，请重新读取；本次不会按完整作业复制。")
            accept(readPage(item.copy(url=cursor.urlFor(index)),begin=true),index)
        }
        val full="<html><body>"+(0 until cursor.total).joinToString("\n"){pages.getValue(it)}+"</body></html>"
        if(full.toByteArray(Charsets.UTF_8).size>MAX_HTML_BYTES)throw ApiProblem("整份题目超过可处理范围，请分题查看。")
        checkActive()
        return HomeworkDetailPage(full,first.url,cursor.total)
    }

    private fun readPage(item: HomeworkItem, begin: Boolean): HomeworkDetailPage {
        checkActive()
        if (item.activityTask || item.peerReview) throw ApiProblem("此类任务请在学习通中查看题目。")
        if (item.url.toHttpUrlOrNull()?.let { HomeworkDetailPolicy.allowed(it, begin) } != true) throw ApiProblem("此作业地址暂不支持读取题目，请在学习通中查看。")
        var target = item.url.toHttpUrlOrNull()!!
        val visited = mutableSetOf<String>()
        var referer = navigationReferer(HomeworkParser.LIST_URL.toHttpUrlOrNull()!!, target)

        repeat(MAX_REDIRECTS + 1) { step ->
            checkActive()
            if (!HomeworkDetailPolicy.allowed(target, begin))
                throw ApiProblem("作业详情跳转到不支持的地址，已停止读取。")
            if (!visited.add(target.toString())) throw ApiProblem("作业详情发生循环跳转，请在学习通中查看。")
            val request = Request.Builder().url(target).get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Referer", referer)
                .apply {
                    val cookieHeader = snapshot.filter { it.expiresAt > System.currentTimeMillis() && it.matches(target) }
                        .joinToString("; ") { "${it.name}=${it.value}" }
                    if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                }.build()
            val call = client.newCall(request)
            synchronized(calls) {
                if (cancelled) throw CancellationException()
                calls.add(call)
            }
            try {
                checkActive()
                call.execute().use { response ->
                    checkActive()
                    if (response.request.url != target || response.request.method != "GET")
                        throw ApiProblem("作业详情请求发生不支持的变化，已停止读取。")
                    if (response.code == 401) throw LoginRequired()
                    if (response.code in REDIRECT_CODES) {
                        val next = response.header("Location")?.takeIf { it.length <= 8192 }
                            ?.let(target::resolve) ?: throw ApiProblem("作业详情跳转地址无法识别。")
                        if (HomeworkDetailPolicy.login(next)) throw LoginRequired()
                        if (step == MAX_REDIRECTS || !HomeworkDetailPolicy.allowed(next, begin))
                            throw ApiProblem("作业详情跳转到不支持的地址，已停止读取。")
                        referer = navigationReferer(target, next)
                        target = next
                        return@repeat
                    }
                    if (!response.isSuccessful) throw ApiProblem("作业题目暂时无法读取（HTTP ${response.code}）。")
                    val body = response.body ?: throw ApiProblem("作业题目返回空响应。")
                    val media = body.contentType()
                    if (media != null && !(media.type == "text" && media.subtype == "html" ||
                            media.type == "application" && media.subtype == "xhtml+xml"))
                        throw ApiProblem("此响应不是可读取的作业题目页面。")
                    if (body.contentLength() > MAX_HTML_BYTES || body.source().request(MAX_HTML_BYTES + 1))
                        throw ApiProblem("作业题目页面过大，已停止读取。")
                    checkActive()
                    val html = body.string()
                    checkPage(html)
                    checkActive()
                    if (target.encodedPath.lowercase() in PREVIEW_PATHS && Jsoup.parse(html).selectFirst(QUESTION_STRUCTURE) == null) {
                        questionLink(html, target, begin)?.let { next ->
                            if (step == MAX_REDIRECTS) throw ApiProblem("作业详情跳转过多，请在学习通中查看。")
                            referer = navigationReferer(target, next)
                            target = next
                            return@repeat
                        }
                    }
                    return HomeworkDetailPage(html, target.toString())
                }
            } catch (error: IOException) {
                checkActive()
                throw error
            } finally {
                synchronized(calls) { calls.remove(call) }
            }
        }
        throw ApiProblem("作业详情跳转过多，请在学习通中查看。")
    }

    private fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
        guard()
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private fun checkPage(html: String) {
        if (html.isBlank()) throw ApiProblem("作业题目页面为空。")
        val doc = Jsoup.parse(html)
        val title = doc.title()
        val text = doc.body().text()
        val hasQuestions = doc.selectFirst(QUESTION_STRUCTURE) != null
        val loginFields = doc.select("input[type=password], form[action*='passport2'], form[action*='/login'], form[action*='/mlogin']")
            .any { it.closest(QUESTION_STRUCTURE) == null }
        if (loginFields || !hasQuestions && (title.contains("登录") ||
                listOf("请先登录", "登录已失效", "登录失效，请", "请重新登录").any(text::contains))) throw LoginRequired()
        val challengeFields = doc.select("form input[name=captcha], form input[name=verifyCode], form input[name=imgcode]")
            .any { it.closest(QUESTION_STRUCTURE) == null }
        if (challengeFields || !hasQuestions && listOf("安全验证", "访问验证", "验证码").any(title::contains))
            throw ApiProblem("学习通要求安全验证，请在官方页面完成后再读取。")
        if (!hasQuestions && (title.contains("信息提示") || text.startsWith("信息提示")) &&
            listOf("无权限", "没有权限", "无权访问", "作业不存在", "无法查看").any(text::contains))
            throw ApiProblem("学习通未允许查看这份作业，请更新作业状态后再核对。")
    }

    private fun navigationReferer(from: HttpUrl, to: HttpUrl): String =
        if (from.host == to.host) from.toString()
        else from.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()

    /** Reads a literal navigation target supplied by the preview; never evaluates or assembles JS. */
    private fun questionLink(html: String, base: HttpUrl, begin: Boolean): HttpUrl? {
        val doc = Jsoup.parse(html)
        val literals = mutableListOf<String>()
        doc.select("a[href]").forEach { literals += it.attr("href") }
        val scripts = doc.select("script").map { it.data() } + doc.select("[onclick]").map { it.attr("onclick") }
        for (script in scripts) for (match in OPEN_URL.findAll(script)) {
            decodeLiteral(match.groupValues[1].ifEmpty { match.groupValues[2] })?.let(literals::add)
        }
        // Real submitted work has preview -> report -> complete-paper navigation. Both
        // pages supply concatenations of literals and literal/hidden-input variables.
        // Resolve only this data; expressions, functions and unknown variables stop here.
        val variables = mutableMapOf<String, MutableList<String?>>()
        for (script in scripts) {
            for (match in JS_CONSTANT.findAll(script)) {
                val value = when {
                    match.groups[2] != null -> decodeLiteral(match.groupValues[2])
                    match.groups[3] != null -> decodeLiteral(match.groupValues[3])
                    else -> match.groupValues[4]
                }
                variables.getOrPut(match.groupValues[1]) { mutableListOf() }.add(value)
            }
            for (match in HIDDEN_CONSTANT.findAll(script)) {
                val inputs = doc.getElementsByAttributeValue("id", match.groupValues[2]).filter {
                    it.normalName() == "input" && it.attr("type").equals("hidden", true)
                }
                val value = inputs.singleOrNull()?.attr("value")?.takeIf { it.length <= 8192 }
                variables.getOrPut(match.groupValues[1]) { mutableListOf() }.add(value)
            }
        }
        val constants = variables.mapValues { it.value.distinct().singleOrNull() }.toMutableMap()
        // The report updates this presentation flag only in a native accessibility
        // bridge callback. No bridge runs in the reader: keep its page-declared default.
        scripts.flatMap { ACCESSIBILITY_DEFAULT.findAll(it).map { match -> match.groupValues[1] }.toList() }
            .distinct().singleOrNull()?.let { constants["isAccessibility"] = it }
        val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        for (script in scripts) for (match in ASSIGNED_OPEN_URL.findAll(script)) {
            staticConcatenation(match.groupValues[2], constants, origin)?.let(literals::add)
        }
        val candidates = literals.mapNotNull { value ->
            if (value.length > 8192 || value.any { it < ' ' || it == '\\' }) null
            else base.resolve(value)
        }.distinct()
        val reads = candidates.filter { HomeworkDetailPolicy.allowed(it) && it.encodedPath.lowercase() in QUESTION_PATHS }
        val startShown = doc.select("input[type=button], input[type=submit], button, a, [onclick]").any {
            it.attr("value").trim() in START_LABELS || it.ownText().trim() in START_LABELS
        }
        if (startShown) {
            val start = candidates.filter {
                it.encodedPath.equals("/mooc-ans/work/phone/doHomeWork", true) && HomeworkDetailPolicy.allowed(it, true)
            }.singleOrNull()
            if (start != null) {
                if (!begin) throw HomeworkStartRequired()
                return start
            }
        }
        if (reads.isNotEmpty()) return reads.singleOrNull()
        return null
    }

    /** A tiny string-concatenation reader, not a JavaScript interpreter. */
    private fun staticConcatenation(expression: String, constants: Map<String, String?>, origin: String): String? {
        val out = StringBuilder()
        var at = 0
        var terms = 0
        while (at < expression.length) {
            while (at < expression.length && expression[at].isWhitespace()) at++
            if (at == expression.length || ++terms > 32) return null
            val value: String
            if (expression[at] == '\'' || expression[at] == '"') {
                val quote = expression[at++]
                val start = at
                var escaped = false
                while (at < expression.length) {
                    val char = expression[at]
                    if (!escaped && char == quote) break
                    escaped = !escaped && char == '\\'
                    at++
                }
                if (at == expression.length) return null
                value = decodeLiteral(expression.substring(start, at++)) ?: return null
            } else {
                val start = at
                while (at < expression.length && (expression[at].isLetterOrDigit() || expression[at] in "_$.")) at++
                if (start == at) return null
                val name = expression.substring(start, at)
                value = when {
                    name == "location.origin" -> origin
                    name.matches(Regex("-?[0-9]{1,10}")) -> name
                    else -> constants[name] ?: return null
                }
            }
            out.append(value)
            if (out.length > 8192) return null
            while (at < expression.length && expression[at].isWhitespace()) at++
            if (at == expression.length) return out.toString()
            if (expression[at++] != '+') return null
        }
        return null
    }

    private fun decodeLiteral(value: String): String? {
        val out = StringBuilder()
        var at = 0
        while (at < value.length) {
            val char = value[at++]
            if (char != '\\') out.append(char)
            else {
                if (at == value.length) return null
                when (val escaped = value[at++]) {
                    '\\', '\'', '"', '/' -> out.append(escaped)
                    'u' -> {
                        if (at + 4 > value.length) return null
                        val code = value.substring(at, at + 4).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        at += 4
                    }
                    else -> return null
                }
            }
        }
        return out.toString()
    }

    companion object {
        internal const val MAX_HTML_BYTES = 2L * 1024 * 1024
        private const val MAX_REDIRECTS = 4
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val QUESTION_PATHS = setOf(
            "/mooc-ans/work/phone/selectworkquestion", "/mooc-ans/work/phone/selectworkquestionyipiyue"
        )
        private val PREVIEW_PATHS = QUESTION_PATHS + "/mooc-ans/work/phone/task-work"
        private val START_LABELS = setOf("开始答题", "开始作答", "进入答题", "继续答题", "继续作答")
        private const val QUESTION_STRUCTURE = ".questionLi, .singleQuesId, .mark_name, .question-title, .question-stem, .qtContent"
        private val OPEN_URL = Regex("""\bprocessOpenUrl\s*\(\s*(?:"((?:\\.|[^"\\\r\n])*)"|'((?:\\.|[^'\\\r\n])*)')\s*[,)]""")
        private val JS_CONSTANT = Regex("""(?:^|(?<=[;{}\r\n]))\s*(?:(?:var|let|const)\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:"((?:\\.|[^"\\\r\n])*)"|'((?:\\.|[^'\\\r\n])*)'|(-?[0-9]{1,10}))\s*;""")
        private val HIDDEN_CONSTANT = Regex("""\b(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*\$\(\s*['"]#([A-Za-z_][A-Za-z0-9_-]*)['"]\s*\)\.val\(\s*\)\s*;""")
        private val ASSIGNED_OPEN_URL = Regex("""\b(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*([^;]{1,16384}?)\s*;\s*processOpenUrl\s*\(\s*\1\s*[,)]""")
        private val ACCESSIBILITY_DEFAULT = Regex("""\b(?:var|let|const)\s+isAccessibility\s*=\s*([01])\s*;""")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
