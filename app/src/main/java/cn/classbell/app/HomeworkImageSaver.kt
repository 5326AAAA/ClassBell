package cn.classbell.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class HomeworkImageSaveFailure(val number: Int, val message: String)
internal data class HomeworkImageSaveResult(val total: Int, val saved: Int, val failures: List<HomeworkImageSaveFailure>) {
    val failed: Int get() = failures.size
}

/** Images are read and written only by an explicit save call. Save and its progress callback run on a worker. */
internal class HomeworkImageSaver(
    cookies: List<Cookie>,
    private val guard: () -> Unit,
    transport: Transport? = null,
    sourcePage: String? = null,
    private val sinkFactory: (Context) -> Sink = { HomeworkMediaStoreSink(it) }
) : AutoCloseable {
    internal interface Transport : AutoCloseable {
        fun execute(request: Request): Response
        fun cancel()
        override fun close() = cancel()
    }
    internal interface Sink {
        fun create(displayName: String, mimeType: String): PendingImage
    }
    internal interface PendingImage {
        fun open(): OutputStream
        fun publish(): Uri
        fun rollback()
    }

    private val jar = SnapshotCookies(cookies.toList())
    private val referer = sourcePage?.toHttpUrlOrNull()?.takeIf { HomeworkDetailPolicy.allowed(it, true) }
        ?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()
    private val transport = transport ?: NetworkTransport(jar)
    private val generation = AtomicInteger()
    private val saving = AtomicBoolean()
    @Volatile private var closed = false

    fun save(context: Context, urls: List<String>, namePrefix: String = "ClassBell",
             onProgress: (Int, Int) -> Unit = { _, _ -> }): HomeworkImageSaveResult {
        if (!saving.compareAndSet(false, true)) throw ApiProblem("已有一批题目图片正在保存。")
        val token = generation.get()
        fun check() {
            if (closed || token != generation.get() || Thread.currentThread().isInterrupted) throw CancellationException()
            guard()
        }
        try {
            check()
            val unique = linkedSetOf<String>()
            for (value in urls) {
                check()
                val source = value.trim()
                unique += imageUrl(source)?.toString() ?: source
                if (unique.size > MAX_IMAGES) throw ApiProblem("一次最多保存 $MAX_IMAGES 张题目图片，请分题保存。")
            }
            onProgress(0, unique.size)
            if (unique.isEmpty()) return HomeworkImageSaveResult(0, 0, emptyList())
            // Legacy permission checks happen here, only after an explicit save action.
            val sink = sinkFactory(context.applicationContext)
            val prefix = namePrefix.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim(' ', '.', '_').take(60).ifBlank { "ClassBell" }
            val batch = UUID.randomUUID().toString().take(8)
            var saved = 0
            val failures = mutableListOf<HomeworkImageSaveFailure>()
            unique.forEachIndexed { index, source ->
                check()
                try {
                    val image = if (source.startsWith("data:", true)) readInline(source, ::check) else readNetwork(source, ::check)
                    check()
                    val format = validate(image)
                    val name = "$prefix-$batch-${(index + 1).toString().padStart(3, '0')}.${format.extension}"
                    write(sink, name, image.bytes, format.mime, ::check)
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    check()
                    val message = when (e) {
                        is ApiProblem -> e.message ?: "图片无法保存。"
                        is SecurityException -> "相册写入权限不可用。"
                        else -> "图片传输或相册写入失败，请重试。"
                    }
                    failures += HomeworkImageSaveFailure(index + 1, message)
                }
                onProgress(index + 1, unique.size)
            }
            check()
            return HomeworkImageSaveResult(unique.size, saved, failures)
        } finally {
            saving.set(false)
        }
    }

    fun cancel() { generation.incrementAndGet(); transport.cancel() }
    override fun close() { closed = true; generation.incrementAndGet(); transport.close() }

    private data class Image(val bytes: ByteArray, val mime: String)
    private enum class Format(val mime: String, val extension: String) {
        PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), GIF("image/gif", "gif"), WEBP("image/webp", "webp")
    }

    private fun readInline(source: String, check: () -> Unit): Image {
        val comma = source.indexOf(',')
        val mime = source.take(comma.takeIf { it >= 0 } ?: 0).lowercase(Locale.ROOT)
            .removePrefix("data:").removeSuffix(";base64")
        if (comma < 0 || !source.substring(0, comma).endsWith(";base64", true) || mime !in MIME_TYPES)
            throw ApiProblem("仅支持 PNG、JPEG、GIF、WebP 的 base64 内嵌图片。")
        if (source.length - comma - 1 > MAX_ENCODED_BYTES + 4096)
            throw ApiProblem("内嵌图片超过 12 MiB 限制。")
        val encoded = StringBuilder(minOf(source.length - comma - 1, MAX_ENCODED_BYTES))
        for (index in comma + 1 until source.length) {
            if (index % 4096 == 0) check()
            val c = source[index]
            if (c in " \r\n\t") continue
            if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "+/="))
                throw ApiProblem("内嵌图片的 base64 内容无效。")
            encoded.append(c)
            if (encoded.length > MAX_ENCODED_BYTES) throw ApiProblem("内嵌图片超过 12 MiB 限制。")
        }
        check()
        val bytes = try { Base64.getDecoder().decode(encoded.toString()) }
        catch (_: IllegalArgumentException) { throw ApiProblem("内嵌图片的 base64 内容无效。") }
        if (bytes.size > MAX_BYTES) throw ApiProblem("内嵌图片超过 12 MiB 限制。")
        check()
        return Image(bytes, mime)
    }

    private fun readNetwork(source: String, check: () -> Unit): Image {
        var target = imageUrl(source) ?: throw ApiProblem("图片地址不受支持，或指向文档附件。")
        repeat(4) { redirects ->
            check()
            val request = Request.Builder().url(target).get()
                .header("Accept", "image/png, image/jpeg, image/gif, image/webp")
                .header("User-Agent", USER_AGENT)
                .apply {
                    referer?.let { header("Referer", it) }
                    val values = jar.loadForRequest(target)
                    if (values.isNotEmpty()) header("Cookie", values.joinToString("; ") { "${it.name}=${it.value}" })
                }.build()
            transport.execute(request).use { response ->
                check()
                if (response.code in 300..399) {
                    if (redirects == 3) throw ApiProblem("图片地址跳转过多。")
                    val next = response.header("Location")?.let(target::resolve)
                    target = imageUrl(next?.toString().orEmpty()) ?: throw ApiProblem("图片跳转到不支持的地址。")
                    return@use
                }
                if (response.code != 200) throw ApiProblem("图片服务器返回 HTTP ${response.code}。")
                val body = response.body ?: throw ApiProblem("图片内容为空。")
                val mime = (response.header("Content-Type") ?: body.contentType()?.toString()).orEmpty()
                    .substringBefore(';').trim().lowercase(Locale.ROOT)
                if (mime !in MIME_TYPES && mime != "application/octet-stream")
                    throw ApiProblem("服务返回网页、文档或不支持的图片格式。")
                val expected = body.contentLength()
                val headerLength = response.header("Content-Length")?.let { it.toLongOrNull() ?: throw ApiProblem("图片长度无效。") }
                if (expected > MAX_BYTES || (headerLength ?: -1L) > MAX_BYTES) throw ApiProblem("图片超过 12 MiB 限制。")
                val bytes = ByteArrayOutputStream(if (expected in 1..MAX_BYTES.toLong()) expected.toInt() else 32 * 1024)
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        check()
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (bytes.size().toLong() + size > MAX_BYTES) throw ApiProblem("图片超过 12 MiB 限制。")
                        bytes.write(buffer, 0, size)
                    }
                }
                check()
                if ((expected >= 0 && bytes.size().toLong() != expected) ||
                    (headerLength != null && headerLength >= 0 && bytes.size().toLong() != headerLength))
                    throw ApiProblem("图片未传输完整，请重试。")
                return Image(bytes.toByteArray(), mime)
            }
        }
        throw ApiProblem("图片地址跳转过多。")
    }

    private fun validate(image: Image): Format {
        val bytes = image.bytes
        fun at(offset: Int, value: String) = offset >= 0 && bytes.size >= offset + value.length &&
            value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
        val format = when {
            bytes.size >= 12 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) && at(bytes.size - 8, "IEND") -> Format.PNG
            bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() &&
                bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte() -> Format.JPEG
            bytes.size >= 14 && (at(0, "GIF87a") || at(0, "GIF89a")) && bytes.last() == 0x3b.toByte() -> Format.GIF
            bytes.size >= 16 && at(0, "RIFF") && at(8, "WEBP") -> Format.WEBP
            else -> throw ApiProblem("图片内容无效、不完整，或格式不受支持。")
        }
        // Some image CDNs use a generic binary MIME and attachment disposition for original images.
        if (image.mime != "application/octet-stream" && format.mime != image.mime)
            throw ApiProblem("图片类型与实际内容不符。")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != format.mime)
            throw ApiProblem("无法识别题目图片。")
        if (bounds.outWidth > 32768 || bounds.outHeight > 32768 || bounds.outWidth.toLong() * bounds.outHeight > 64L * 1024 * 1024)
            throw ApiProblem("图片尺寸过大，未保存。")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048 ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 1024L * 1024) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ApiProblem("图片内容无法解码，未保存。")
        decoded.recycle()
        return format
    }

    private fun write(sink: Sink, name: String, bytes: ByteArray, mime: String, check: () -> Unit) {
        check()
        val pending = sink.create(name, mime)
        var published = false
        var failure: Throwable? = null
        try {
            check()
            pending.open().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    check()
                    val size = minOf(32 * 1024, bytes.size - offset)
                    output.write(bytes, offset, size); offset += size
                }
                output.flush()
            }
            check()
            pending.publish()
            check()
            published = true
        } catch (e: Throwable) { failure = e; throw e }
        finally {
            if (!published) try { pending.rollback() }
            catch (cleanup: Exception) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private class SnapshotCookies(values: List<Cookie>) : CookieJar {
        private val snapshot = values.filter { firstParty(it.domain) }.toList()
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (imageUrl(url.toString()) != null && firstParty(url.host)) snapshot.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }
    private class NetworkTransport(jar: CookieJar) : Transport {
        private val closed = AtomicBoolean()
        private val client = OkHttpClient.Builder().cookieJar(jar)
            .eventListenerFactory(DiagnosticLog.httpFactory("HomeworkImage"))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
        override fun execute(request: Request) = client.newCall(request).execute()
        private fun cleanup(action: () -> Unit) {
            try { client.dispatcher.executorService.execute {
                try { action() } catch (error: Exception) { DiagnosticLog.error("HomeworkImageCleanup", error) }
            } } catch (_: RejectedExecutionException) { /* close already queued the final cancellation */ }
        }
        override fun cancel() { if (!closed.get()) cleanup { client.dispatcher.cancelAll() } }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            // Closing a pooled TLS socket can write close_notify on Android 17.
            // Activity teardown must never do that network I/O on the main thread.
            cleanup { try { client.dispatcher.cancelAll() } finally { client.connectionPool.evictAll() } }
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        const val MAX_IMAGES = 300
        const val MAX_BYTES = 12 * 1024 * 1024
        private const val MAX_ENCODED_BYTES = ((MAX_BYTES + 2) / 3) * 4
        private val MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
        private val inlineHeader = Regex("data:image/(?:png|jpeg|gif|webp);base64,", RegexOption.IGNORE_CASE)
        private val cloudImageHost = Regex("(?:d[0-9]{1,2}|s[0-9]{1,2}|cs)\\.cldisk\\.com")
        private val attachmentExtension = Regex("\\.(?:pdf|docx?|xlsx?|pptx?|zip|rar|7z|tar|gz|txt|csv|epub|apk|exe|mp[34]|m4a|wav)$", RegexOption.IGNORE_CASE)
        private fun firstParty(host: String) = host == "chaoxing.com" || host.endsWith(".chaoxing.com")
        fun imageUrl(value: String): HttpUrl? {
            if (value.length > 4096 || value.any { it < ' ' || it == '\\' }) return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
                !(firstParty(url.host) || cloudImageHost.matches(url.host))) return null
            if (attachmentExtension.containsMatchIn(url.pathSegments.lastOrNull().orEmpty())) return null
            for (key in url.queryParameterNames.filter { it.lowercase(Locale.ROOT) in setOf("filename", "name", "file", "download") })
                if (url.queryParameterValues(key).filterNotNull().any { attachmentExtension.containsMatchIn(it) }) return null
            return url
        }
        fun supportedSource(value: String): Boolean = imageUrl(value) != null ||
            (value.length <= MAX_ENCODED_BYTES + 4096 + 64 && inlineHeader.find(value)?.range?.first == 0)
        fun requiresLegacyPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }
}

/** Own images need no broad media read permission. API 26–28 publish only after the hidden draft is complete. */
internal class HomeworkMediaStoreSink(private val context: Context, private val legacyFolder: File? = null) : HomeworkImageSaver.Sink {
    private val resolver = context.contentResolver
    init {
        if (HomeworkImageSaver.requiresLegacyPermission(context)) throw SecurityException("需要允许保存图片到相册。")
    }
    override fun create(displayName: String, mimeType: String): HomeworkImageSaver.PendingImage =
        if (Build.VERSION.SDK_INT >= 29) modern(displayName, mimeType) else legacy(displayName, mimeType)

    private fun values(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
    }
    private fun modern(name: String, mime: String): HomeworkImageSaver.PendingImage {
        val values = values(name, mime).apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ClassBell")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("无法创建相册图片。")
        return object : HomeworkImageSaver.PendingImage {
            override fun open() = resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入相册图片。")
            override fun publish(): Uri {
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1)
                    throw IOException("无法完成相册图片保存。")
                return uri
            }
            override fun rollback() { resolver.delete(uri, null, null) }
        }
    }
    @Suppress("DEPRECATION")
    private fun legacy(name: String, mime: String): HomeworkImageSaver.PendingImage {
        val folder = legacyFolder ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ClassBell")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建相册文件夹。")
        val target = File(folder, name)
        if (target.exists()) throw IOException("相册中已有同名图片。")
        val draft = File.createTempFile(".classbell-", ".pending", folder)
        return object : HomeworkImageSaver.PendingImage {
            private var moved = false
            private var uri: Uri? = null
            override fun open(): OutputStream = draft.outputStream()
            override fun publish(): Uri {
                if (!draft.renameTo(target)) throw IOException("无法完成相册图片写入。")
                moved = true
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values(name, mime).apply {
                    put(MediaStore.Images.Media.DATA, target.absolutePath)
                }) ?: throw IOException("无法登记相册图片。")
                return uri!!
            }
            override fun rollback() {
                try { uri?.let { resolver.delete(it, null, null) } }
                finally {
                    if (draft.exists() && !draft.delete()) throw IOException("无法清理相册草稿。")
                    if (moved && target.exists() && !target.delete()) throw IOException("无法清理未完成图片。")
                }
            }
        }
    }
}
