package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CourseMaterial(val name: String, val original: HttpUrl?, val pdf: HttpUrl?,
                                   val sourceOrigin: String? = null, val userAgent: String? = null) {
    val pdfName: String get() = name.substringBeforeLast('.') + ".pdf"

    companion object {
        private val objectId = Regex("[a-f0-9]{32}", RegexOption.IGNORE_CASE)
        private val fileHost = Regex("(?:d[0-9]{1,2}|s[0-9]{1,2}|cs)\\.cldisk\\.com")
        private val unsafeName = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun previewId(url: HttpUrl): String? {
            if (!OfficialPagePolicy.allowed(url.toString()) || url.fragment != null) return null
            if (url.pathSegments.getOrNull(url.pathSegments.size - 2) != "status") return null
            return url.pathSegments.last().takeIf { objectId.matches(it) }?.lowercase()
        }

        fun fileUrl(value: String): HttpUrl? {
            val url = value.takeIf { it.length <= 4096 }?.toHttpUrlOrNull() ?: return null
            if (!fileHost.matches(url.host) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
                return null
            return when {
                url.isHttps && url.port == 443 -> url
                url.scheme == "http" && url.port == 80 -> url.newBuilder().scheme("https").port(443).build()
                else -> null
            }
        }

        fun parse(id: String, body: String): CourseMaterial? {
            val data = diagnosticRunCatching { JSONObject(body) }.getOrNull() ?: return null
            if (data.optString("status") != "success" ||
                !data.optString("objectid", data.optString("objectId")).equals(id, ignoreCase = true)) return null
            val original = fileUrl(data.optString("download"))
            val pdf = fileUrl(data.optString("pdf"))
            if (original == null && pdf == null) return null
            val name = data.optString("filename").replace(unsafeName, "_").take(140).trim(' ', '.').ifBlank { "课程资料" }
            return CourseMaterial(name, original, pdf)
        }
    }
}

internal data class CoursePreviewRequest(val url: HttpUrl, val headers: Map<String, String>) {
    val origin: String get() = url.newBuilder().encodedPath("/").query(null).build().toString()

    companion object {
        fun from(url: HttpUrl, webHeaders: Map<String, String>, page: String?, userAgent: String): CoursePreviewRequest {
            fun header(name: String) = webHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value
                ?.takeIf { it.length <= 4096 && it.all { c -> c in ' '..'~' } }
            fun officialReferer(value: String?): String? = value?.takeIf(OfficialPagePolicy::allowed)
                ?.toHttpUrlOrNull()?.let { referer ->
                    val safe = referer.newBuilder().fragment(null)
                    if (referer.host != url.host) safe.encodedPath("/").query(null)
                    safe.build().toString()
                }
            val referer = officialReferer(header("Referer")) ?: officialReferer(page)
                ?: url.newBuilder().encodedPath("/").query(null).build().toString()
            val headers = linkedMapOf(
                "Referer" to referer,
                "User-Agent" to (header("User-Agent") ?: userAgent),
                "Accept" to (header("Accept") ?: "application/json, text/javascript, */*; q=0.01"),
                "X-Requested-With" to (header("X-Requested-With") ?: "XMLHttpRequest")
            )
            return CoursePreviewRequest(url, headers)
        }
    }
}

/** Collects preview requests from the current course page, then saves the files the user selects. */
internal class CourseMaterialDownloads(
    private val activity: Activity,
    private val cookies: List<Cookie>,
    private val current: () -> Boolean,
    private val status: (String) -> Unit
) {
    private val previews = linkedMapOf<String, CoursePreviewRequest>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("CourseMaterialDownloads")).followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    private var busy = false
    private var pending = emptyList<CourseMaterial>()
    private var dialog: AlertDialog? = null
    @Volatile private var closed = false

    fun observe(url: String, method: String, headers: Map<String, String>, page: String?, userAgent: String) {
        if (closed || method != "GET" || url.length > 4096) return
        val request = url.toHttpUrlOrNull() ?: return
        val id = CourseMaterial.previewId(request) ?: return
        synchronized(previews) {
            if (id in previews || previews.size < 60) previews[id] = CoursePreviewRequest.from(request, headers, page, userAgent)
        }
    }

    fun show() {
        if (busy || pending.isNotEmpty() || !current()) return
        val batch = synchronized(previews) { previews.toMap() }
        if (batch.isEmpty()) {
            ModernUi.dialog(activity).setTitle("批量下载资料")
                .setMessage("先点开课程资料的文件预览，等页面载入，再回来批量下载。")
                .setPositiveButton("知道了", null).show()
            return
        }
        busy = true
        status("正在读取 ${batch.size} 项预览的文件信息…")
        DiagnosticLog.event("Material", "previews_begin count=${batch.size}")
        worker.execute {
            val items = mutableListOf<CourseMaterial>()
            val errors = mutableListOf<String>()
            for ((id, request) in batch) {
                if (closed || !current() || Thread.currentThread().isInterrupted) break
                try { items += readPreview(id, request) }
                catch (diagnosticError: CancellationException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError); break }
                catch (e: ApiProblem) { DiagnosticLog.error("CourseMaterialDownloads", e); errors += e.message.orEmpty() }
                catch (diagnosticError: java.io.IOException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError); errors += "预览请求失败，请检查网络" }
            }
            main.post {
                busy = false
                if (closed || !current()) return@post
                val summary = "找到 ${items.size} 项可保存资料"
                DiagnosticLog.event("Material", "previews_end available=${items.size} failed=${errors.size}")
                val problem = errors.distinct().take(2).joinToString("、")
                status(if (errors.isEmpty()) summary else "$summary · ${errors.size} 项未读到预览（$problem）")
                if (items.isNotEmpty()) choose(items)
            }
        }
    }

    private fun readPreview(id: String, preview: CoursePreviewRequest): CourseMaterial = DiagnosticLog.operation("MaterialPreview", "file_ref=${DiagnosticLog.reference(id)} endpoint=${DiagnosticLog.endpoint(preview.url.toString())}") { readPreviewFile(id,preview).also { DiagnosticLog.event("MaterialPreview", "file=${DiagnosticLog.label(it.name)} original=${it.original!=null} pdf=${it.pdf!=null}") } }

    private fun readPreviewFile(id: String, preview: CoursePreviewRequest): CourseMaterial {
        guard()
        val url = preview.url
        val cookie = CookieManager.getInstance().getCookie(url.toString())
            ?: cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                .joinToString("; ") { "${it.name}=${it.value}" }
        val request = Request.Builder().url(url).apply {
            preview.headers.forEach { (name, value) -> header(name, value) }
            if (cookie.isNotEmpty()) header("Cookie", cookie)
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.isRedirect) throw ApiProblem("预览发生跳转，请重新打开文件")
            if (!response.isSuccessful) throw ApiProblem("预览服务器返回 HTTP ${response.code}")
            val body = response.body ?: throw ApiProblem("预览响应为空")
            if (body.source().request(128 * 1024L + 1)) throw ApiProblem("预览响应过大")
            val material = CourseMaterial.parse(id, body.string()) ?: throw ApiProblem("预览响应没有可用文件或 PDF")
            return material.copy(sourceOrigin = preview.origin, userAgent = preview.headers["User-Agent"])
        }
    }

    private fun choose(items: List<CourseMaterial>) {
        val checked = BooleanArray(items.size) { true }
        dialog?.dismiss()
        val builder = ModernUi.dialog(activity).setTitle("勾选要保存的资料")
            .setMultiChoiceItems(items.map {
                when {
                    it.original == null -> "${it.pdfName} · 仅 PDF"
                    it.pdf != null -> "${it.name} · 有 PDF"
                    else -> it.name
                }
            }.toTypedArray(), checked) { _, index, value ->
                checked[index] = value
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("选文件夹") { _, _ ->
                pickFolder(items.filterIndexed { index, _ -> checked[index] })
            }
        if (items.any { it.original != null && it.pdf != null }) {
            builder.setNeutralButton("仅存 PDF") { _, _ ->
                val selected = items.filterIndexed { index, _ -> checked[index] }
                val pdfs = selected.filter { it.pdf != null }.map { it.copy(original = null) }
                if (pdfs.isEmpty()) {
                    status("所选资料没有可保存的 PDF。")
                } else {
                    if (pdfs.size != selected.size) status("将保存 ${pdfs.size} 项 PDF · ${selected.size - pdfs.size} 项没有 PDF")
                    pickFolder(pdfs)
                }
            }
        }
        dialog = builder.create().apply { setOnDismissListener { dialog = null }; show() }
    }

    private fun pickFolder(selected: List<CourseMaterial>) {
        if (selected.isEmpty() || !current()) return
        pending = selected
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION") activity.startActivityForResult(picker, REQUEST)
        } catch (diagnosticError: android.content.ActivityNotFoundException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError);
            pending = emptyList()
            status("系统文件夹选择器不可用。")
        }
    }

    fun save(tree: Uri?) {
        val batch = pending
        DiagnosticLog.event("Material", "folder_result selected=${tree!=null} session_valid=${current()} pending=${batch.size}")
        pending = emptyList()
        if (!current() || tree == null || batch.isEmpty() || tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) return
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        busy = true
        status("正在保存 ${batch.size} 项课程资料…")
        DiagnosticLog.event("Material", "download_batch_begin count=${batch.size}")
        worker.execute {
            var originals = 0
            var pdfs = 0
            val failed = mutableListOf<String>()
            for (item in batch) {
                if (closed || !current() || Thread.currentThread().isInterrupted) break
                try {
                    val original = item.original
                    val pdf = item.pdf
                    val originalError = original?.let {
                        try { download(item, it, folder, false); null }
                        catch (e: ApiProblem) { DiagnosticLog.error("CourseMaterialDownloads", e); e }
                        catch (e: java.io.IOException) { DiagnosticLog.error("CourseMaterialDownloads", e); e }
                    }
                    if (original != null && originalError == null) originals++
                    else if (pdf != null) { DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} fallback=pdf original_available=${original!=null} reason=${originalError?.let{DiagnosticLog.failureKind(it)} ?: "no_original"}");download(item, pdf, folder, true); pdfs++ }
                    else throw originalError ?: ApiProblem("没有可用下载地址")
                } catch (diagnosticError: CancellationException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError);
                    break
                } catch (diagnosticError: SecurityException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError);
                    failed += "${item.name} · 文件夹没有写入权限"
                } catch (e: MaterialFolderProblem) { DiagnosticLog.error("CourseMaterialDownloads", e);
                    failed += "${item.name} · ${e.message}"
                } catch (e: ApiProblem) { DiagnosticLog.error("CourseMaterialDownloads", e);
                    failed += "${item.name} · ${e.message}"
                } catch (diagnosticError: java.io.IOException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError);
                    failed += "${item.name} · 文件传输中断"
                }
            }
            main.post {
                busy = false
                if (closed || !current()) return@post
                val result = "已保存 ${originals + pdfs} 项 · 原文件 $originals · PDF $pdfs · 失败 ${failed.size}"
                DiagnosticLog.event("Material", "download_batch_end original=$originals pdf=$pdfs failed=${failed.size}")
                status(result)
                if (failed.isNotEmpty()) dialog = ModernUi.dialog(activity).setTitle("批量下载结果")
                    .setMessage("$result\n\n未能保存\n" + failed.take(12).joinToString("\n"))
                    .setPositiveButton("知道了", null).create().apply { setOnDismissListener { dialog = null }; show() }
            }
        }
    }

    private fun download(item: CourseMaterial, initial: HttpUrl, folder: Uri, pdf: Boolean) = DiagnosticLog.operation("MaterialDownload", "file=${DiagnosticLog.label(item.name)} pdf=$pdf endpoint=${DiagnosticLog.endpoint(initial.toString())}") { downloadFile(item,initial,folder,pdf) }

    private fun downloadFile(item: CourseMaterial, initial: HttpUrl, folder: Uri, pdf: Boolean) {
        var url = initial
        repeat(4) {
            guard()
            val request = Request.Builder().url(url).apply {
                item.sourceOrigin?.let { header("Referer", it) }
                item.userAgent?.let { header("User-Agent", it) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = url.resolve(response.header("Location").orEmpty())
                    url = CourseMaterial.fileUrl(next?.toString().orEmpty()) ?: throw ApiProblem("下载地址无效。")
                    return@use
                }
                val body = response.body ?: throw ApiProblem("未返回文件。")
                if (!response.isSuccessful) throw ApiProblem("文件服务器返回 HTTP ${response.code}")
                val expectedBytes = body.contentLength()
                DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} transfer_begin expected_bytes=$expectedBytes pdf=$pdf")
                if (expectedBytes > MAX_BYTES) throw ApiProblem("文件超过大小限制")
                val type = body.contentType()
                if (type?.subtype in setOf("html", "json")) throw ApiProblem("服务返回网页提示。")
                val source = body.source()
                if (!source.request(1) || (pdf && (!source.request(5) || source.peek().readUtf8(5) != "%PDF-")))
                    throw ApiProblem("文件内容无效。")
                guard()
                DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} create_document=true")
                val resolver = activity.contentResolver
                val document = DocumentsContract.createDocument(resolver, folder, if (pdf) "application/pdf" else
                    type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream", if (pdf) item.pdfName else item.name)
                    ?: throw MaterialFolderProblem("无法在所选文件夹创建文件")
                try {
                    resolver.openOutputStream(document)?.use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            guard()
                            val size = source.read(buffer)
                            if (size == -1) break
                            total += size
                            if (total > MAX_BYTES) throw ApiProblem("文件超过大小限制。")
                            try { output.write(buffer, 0, size) }
                            catch (diagnosticError: java.io.IOException) { DiagnosticLog.error("CourseMaterialDownloads", diagnosticError); throw MaterialFolderProblem("文件夹写入失败") }
                        }
                        if (expectedBytes >= 0 && total != expectedBytes) {DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} incomplete expected=$expectedBytes received=$total", "WARN");throw java.io.IOException("Incomplete download")}
                        DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} written_bytes=$total pdf=$pdf")
                    } ?: throw MaterialFolderProblem("无法打开文件写入")
                } catch (error: Exception) { DiagnosticLog.error("CourseMaterialDownloads", error);
                    val removed=diagnosticRunCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false)
                    DiagnosticLog.event("Material", "file=${DiagnosticLog.label(item.name)} incomplete_document_removed=$removed", "WARN")
                    throw error
                }
                return
            }
        }
        throw ApiProblem("下载地址跳转过多。")
    }

    private fun guard() {
        if (closed || !current() || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private class MaterialFolderProblem(message: String) : Exception(message)

    fun close() {
        closed = true
        dialog?.dismiss()
        client.dispatcher.cancelAll()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        synchronized(previews) { previews.clear() }
    }

    companion object {
        const val REQUEST = 5103
        private const val MAX_BYTES = 256L * 1024 * 1024
    }
}
