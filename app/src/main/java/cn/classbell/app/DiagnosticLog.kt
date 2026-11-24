package cn.classbell.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Only curated metadata goes here. Never pass payloads, credentials, form inputs or notification text. */
object DiagnosticLog {
    internal const val SEGMENT_BYTES = 2 * 1024 * 1024
    private val lock = Any()
    @Volatile private var directory: File? = null
    @Volatile private var session = ""
    @Volatile private var storageError = false
    private val dropped = AtomicInteger()
    private val sequence = AtomicLong()
    private val scope = ThreadLocal<String>()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1000),
        { r -> Thread(r, "diagnostic-writer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun init(app: Application) {
        directory = File(app.filesDir, "diagnostics")
        session = UUID.randomUUID().toString().take(8)
        event("App", "process_start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} android=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = event("Page", "created ${a.javaClass.simpleName}")
            override fun onActivityResumed(a: Activity) = event("Page", "resumed ${a.javaClass.simpleName}")
            override fun onActivityPaused(a: Activity) = event("Page", "paused ${a.javaClass.simpleName}")
            override fun onActivityDestroyed(a: Activity) = event("Page", "destroyed ${a.javaClass.simpleName}")
            override fun onActivityStarted(a: Activity) = event("Page", "started ${a.javaClass.simpleName}")
            override fun onActivityStopped(a: Activity) = event("Page", "stopped ${a.javaClass.simpleName} finishing=${a.isFinishing}")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = event("Page", "state_saved ${a.javaClass.simpleName}")
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashHandler) Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            // Write synchronously: the Android handler will terminate this process.
            append(line("ERROR", "Crash", "uncaught thread=${thread.name} ${exceptionSummary(error)}"))
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    fun event(area: String, message: String, level: String = "INFO") {
        if (directory == null) return
        val entry = line(level, area, message)
        try { writer.execute { append(entry) } } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }
    fun error(area: String, error: Throwable) = event(area, exceptionSummary(error),
        if (error is java.util.concurrent.CancellationException || error is InterruptedException) "INFO" else "ERROR")

    internal fun exceptionSummary(error: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(5).joinToString(" caused_by ") { e ->
            // Arbitrary exception messages can contain passwords, signed URLs and server bodies.
            e.javaClass.simpleName + " category=${failureKind(e)}" + (if(e is ApiProblem || e is LoginRequired) " reason=${label(e.message.orEmpty())}" else "") + e.stackTrace.take(18).joinToString(" <- ", prefix = " at ") {
                "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
        }
    }

    fun failureKind(error: Throwable): String = when(error) {
        is java.util.concurrent.CancellationException, is InterruptedException -> "cancelled"
        is LoginRequired -> "login_expired"
        is java.net.UnknownHostException -> "dns"
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is java.net.ConnectException -> "connect"
        is java.io.InterruptedIOException -> "timeout_or_interrupted"
        is java.io.IOException -> "io"
        is java.security.GeneralSecurityException -> "encryption"
        is SecurityException -> "permission"
        is org.json.JSONException -> "json_format"
        is ApiProblem -> "service_or_format"
        else -> "unexpected"
    }

    /** Only known course/work/file titles; never form text, credentials or response bodies. */
    fun label(value: String): String = "\"" + sanitize(value).replace('"', '＂').take(200).ifBlank { "未识别" } + "\""
    fun homework(item: HomeworkItem) = "course=${label(item.courseName)} work=${label(item.title)} item=${reference(item.key)}"
    fun course(course: Course) = "course=${label(course.name)} course_ref=${reference(course.key)}"
    fun activity(item: SignActivity) = "${course(item.course)} activity=${label(item.title)} activity_ref=${reference(item.id)}"

    fun reference(value: String): String = if (value.isBlank()) "none" else
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    fun endpoint(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return "unrecognized-url"
        // No query values, fragments, user info, path IDs or file names.
        val path = url.pathSegments.joinToString("/") { segment ->
            if (segment in setOf("mooc2", "v2", "userLogin4Uname.do") || segment.matches(Regex("[A-Za-z_-]{1,48}(?:\\.(?:do|html|json|jsp))?"))) segment else "[id]"
        }
        return "${url.scheme}://${url.host}/$path"
    }

    internal fun sanitize(value: String): String = value
        .replace(Regex("https?://[^\\s<>\"']+")) { endpoint(it.value) }
        .replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]*"), "$1=[redacted]")
        .replace(Regex("(?<![A-Za-z0-9_])(Code|c|(?i:rcode))[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"), "$1=[redacted]")
        .replace(Regex("(?i)(password|passwd|token|secret|_uid|puid|openid|unionid|wxsn|_wtime|phone|signCode|enc|gesture|latitude|longitude|手机号|密码)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"), "$1=[redacted]")
        .replace(Regex("(?<![A-Za-z0-9])[0-9]{7,}(?![A-Za-z0-9])"), "[number]")
        .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(6000)

    private fun line(level: String, area: String, message: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return "$time $level [$session/${sequence.incrementAndGet()}] [${sanitize(area)}] [op=${scope.get() ?: "-"}] ${sanitize(message)}\n"
    }
    private fun append(entry: String) {
        synchronized(lock) {
            try {
                val dir = directory ?: return
                check(dir.isDirectory || dir.mkdirs())
                val file = File(dir, "current.log")
                if (file.length() + entry.toByteArray(Charsets.UTF_8).size > SEGMENT_BYTES) {
                    File(dir, "3.log").let { if (it.exists()) check(it.delete()) }
                    for (index in 2 downTo 1) File(dir, "$index.log").let { if (it.exists()) check(it.renameTo(File(dir, "${index + 1}.log"))) }
                    if (file.exists()) check(file.renameTo(File(dir, "1.log")))
                }
                file.appendText(entry, Charsets.UTF_8)
                storageError = false
            } catch (_: Exception) { storageError = true }
        }
    }

    /** Called from a worker only, never the UI thread. */
    fun snapshot(): String {
        writer.submit {}.get(10, TimeUnit.SECONDS)
        return synchronized(lock) {
            val dir = directory ?: return@synchronized "日志尚未初始化。"
            val header = "Class Bell ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) 诊断日志\n" +
                "时间包含手机时区；含课程、作业、活动与资料名称，账号使用匿名标识。无密码、Cookie、正文、坐标或照片。\n" +
                "日志最多保留约 8 MiB；队列丢弃=${dropped.get()}；写入异常=$storageError\n\n"
            header + listOf("3.log", "2.log", "1.log", "current.log").joinToString("") {
                File(dir, it).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
            }
        }
    }
    fun clear() {
        writer.submit {
            synchronized(lock) { directory?.listFiles()?.filter { it.name in setOf("current.log", "1.log", "2.log", "3.log") }?.forEach { check(it.delete()) } }
            dropped.set(0)
        }.get(10, TimeUnit.SECONDS)
        event("Log", "cleared_by_user")
    }
    fun <T> operation(area: String, details: String = "", action: () -> T): T {
        val old = scope.get()
        val id = "${session}-${sequence.incrementAndGet()}"
        scope.set(id)
        val start = System.nanoTime()
        event(area, "begin parent=${old ?: "-"} $details")
        try { return action().also { event(area, "complete elapsed_ms=${(System.nanoTime() - start) / 1_000_000}") } }
        catch (e: Exception) { event(area, "failed $details category=${failureKind(e)} elapsed_ms=${(System.nanoTime() - start) / 1_000_000}", if(failureKind(e)=="cancelled")"INFO" else "WARN"); error(area, e); throw e }
        finally { scope.set(old) }
    }

    fun httpFactory(area: String) = EventListener.Factory { call ->
        val requestId = sequence.incrementAndGet()
        val operationId = scope.get() ?: "-"
        object : EventListener() {
            private val started = System.nanoTime()
            private var phase = "created"
            private var connections = 0
            private fun record(stage: String) = event(area, "request=$requestId parent=$operationId $stage elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            override fun callStart(call: Call) = record("start ${call.request().method} ${endpoint(call.request().url.toString())}")
            override fun dnsStart(call: Call, domainName: String) { phase="dns";record("dns_start host=$domainName") }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = record("dns_end count=${inetAddressList.size}")
            override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {phase="connect";connections++;record("connect_start attempt=$connections proxy=${proxy.type()}")}
            override fun secureConnectStart(call: Call) {phase="tls";record("tls_start")}
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = record("tls_end version=${handshake?.tlsVersion}")
            override fun connectionAcquired(call: Call, connection: Connection) = record("connection_acquired protocol=${connection.protocol()} fresh_attempts=$connections")
            override fun requestHeadersStart(call: Call) {phase="request_headers";record("request_headers_start")}
            override fun requestHeadersEnd(call:Call,request:Request) {
                request.tag(QrDispatchTrace::class.java)?.let { trace->
                    val elapsed=android.os.SystemClock.elapsedRealtime()-trace.capturedAt
                    event("QrTiming","headers_sent_ms=$elapsed within_5s=${elapsed in 0..5_000}")
                }
            }
            override fun requestBodyEnd(call: Call, byteCount: Long) = record("request_body_end bytes=$byteCount")
            override fun responseHeadersStart(call: Call) {phase="response_headers";record("response_headers_start")}
            override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { record("connect_failed"); error("$area/request=$requestId", ioe) }
            override fun responseHeadersEnd(call: Call, response: Response) = record("headers http=${response.code} redirect=${response.header("Location")?.let(::endpoint) ?: "none"}")
            override fun responseBodyEnd(call: Call, byteCount: Long) = record("body_end bytes=$byteCount")
            override fun responseBodyStart(call: Call) {phase="response_body";record("body_start")}
            override fun callEnd(call: Call) = record("end")
            override fun callFailed(call: Call, ioe: IOException) { record("failed phase=$phase cancelled=${call.isCanceled()} reason=${failureKind(ioe)} attempts=$connections"); error("$area/request=$requestId", ioe) }
        }
    }
}

/** Button action prefix only; never account labels or text field values. */
fun View.setLoggedClickListener(action: (View) -> Unit) {
    val origin = Throwable().stackTrace.firstOrNull { it.className.startsWith("cn.classbell.app.") && !it.className.endsWith("DiagnosticLogKt") }
    val source = "${origin?.fileName}:${origin?.lineNumber}"
    setOnClickListener { view ->
        val title = if(view is android.widget.Button) view.text.toString().substringBefore(" · ").substringBefore("（").let {
            if(it.startsWith("解绑 "))"解绑账号" else if(it.startsWith("为 ") && it.endsWith(" 选择照片"))"选择账号照片" else if(it.startsWith("移除 ") && it.endsWith(" 的本次照片"))"移除本次照片" else if(it.length<=32)it else "按钮操作"
        } else view.javaClass.simpleName
        DiagnosticLog.operation("UI", "action=${DiagnosticLog.label(title)} click=$source view=${view.javaClass.simpleName}") { action(view) }
    }
}

/** Same Result/cancellation semantics as runCatching, with failures preserved in diagnostics. */
inline fun <T> diagnosticRunCatching(block: () -> T): Result<T> = try { Result.success(block()) }
catch (error: Throwable) { DiagnosticLog.error("RecoveredFailure", error); Result.failure(error) }
