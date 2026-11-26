package cn.classbell.app

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import okhttp3.Cookie
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeworkImageSaverTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val first = "https://p.ananas.chaoxing.com/star3/origin/synthetic-1.png"
    private val second = "https://p.ananas.chaoxing.com/star3/origin/synthetic-2.png"
    private fun picture(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 95, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun animatedGif(): ByteArray {
        val frame = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        return frame.copyOf(frame.size - 1) + frame.copyOfRange(19, frame.size - 1) + byteArrayOf(0x3b)
    }
    private fun inline(bytes: ByteArray, mime: String = "image/png") = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    private fun response(request: Request, bytes: ByteArray, mime: String = "image/png", code: Int = 200,
                         headers: Map<String, String> = emptyMap(), body: ResponseBody? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
            .header("Content-Type", mime).apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body ?: bytes.toResponseBody(mime.toMediaType())).build()

    private class FakeTransport(private val answer: (Request) -> Response) : HomeworkImageSaver.Transport {
        val requests = mutableListOf<Request>()
        var cancellations = 0
        override fun execute(request: Request): Response { requests += request; return answer(request) }
        override fun cancel() { cancellations++ }
    }
    private class FakeSink : HomeworkImageSaver.Sink {
        val drafts = mutableListOf<Draft>()
        var onWrite: () -> Unit = {}
        var failPublish = emptySet<Int>()
        override fun create(displayName: String, mimeType: String): HomeworkImageSaver.PendingImage =
            Draft(displayName, mimeType, drafts.size + 1).also { drafts += it }
        inner class Draft(val name: String, val mime: String, private val number: Int) : HomeworkImageSaver.PendingImage {
            val bytes = ByteArrayOutputStream()
            var published = false
            var rolledBack = false
            var streamClosed = false
            override fun open(): OutputStream = object : OutputStream() {
                override fun write(b: Int) { bytes.write(b); onWrite() }
                override fun write(b: ByteArray, off: Int, len: Int) { bytes.write(b, off, len); onWrite() }
                override fun close() { streamClosed = true }
            }
            override fun publish(): Uri {
                assertTrue("Stream must close before publication", streamClosed)
                if (number in failPublish) throw IOException("synthetic album failure")
                published = true
                return Uri.parse("content://synthetic-album/$number")
            }
            override fun rollback() { rolledBack = true; published = false }
        }
    }
    private fun isolatedSaver(transport: FakeTransport, sink: FakeSink, cookies: List<Cookie> = emptyList(), guard: () -> Unit = {}) =
        HomeworkImageSaver(cookies, guard, transport) { sink }

    @Test fun imageRequestsUseMobileHeadersAndOnlyTheApprovedPageOriginWithoutItsSignature() {
        val png=picture()
        for(page in listOf("https://mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestionYiPiYue?enc=private-signature",
            "https://foreign.invalid/mooc-ans/work/phone/selectWorkQuestion?enc=private-signature")) {
            val transport=FakeTransport{response(it,png)};val sink=FakeSink()
            val saver=HomeworkImageSaver(emptyList(),{},transport,sourcePage=page){sink}
            try {
                assertEquals(1,saver.save(app,listOf(first)).saved)
                val request=transport.requests.single()
                assertTrue(request.header("User-Agent")!!.contains("Mobile Safari"))
                assertEquals(if(page.contains("foreign.invalid"))null else "https://mooc1-api.chaoxing.com/",request.header("Referer"))
                assertFalse(request.headers.toString().contains("private-signature"))
            }finally{saver.close()}
        }
    }
    @Test fun constructionDoesNothingAndExplicitBatchDeduplicatesInOrderWithoutChangingPixels() {
        val png = picture()
        val transport = FakeTransport { response(it, png) }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            assertTrue(transport.requests.isEmpty()); assertTrue(sink.drafts.isEmpty())
            val progress = mutableListOf<Pair<Int, Int>>()
            val result = saver.save(app, listOf(first, second, first), "../合成题目:\n") { a, b -> progress += a to b }
            assertEquals(2, result.total); assertEquals(2, result.saved); assertEquals(0, result.failed)
            assertEquals(listOf(first, second), transport.requests.map { it.url.toString() })
            assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
            assertTrue(sink.drafts[0].name.endsWith("-001.png")); assertTrue(sink.drafts[1].name.endsWith("-002.png"))
            sink.drafts.forEach {
                assertFalse(it.name.any { c -> c in "/\\:\n\r" }); assertTrue(it.published); assertFalse(it.rolledBack)
                assertArrayEquals(png, it.bytes.toByteArray())
            }
        } finally { saver.close() }
    }

    @Suppress("DEPRECATION")
    @Test fun fourInlineFormatsAreDecodedOnlyOnSaveAndOriginalAnimatedGifBytesArePreserved() {
        val images = listOf("image/png" to picture(), "image/jpeg" to picture(Bitmap.CompressFormat.JPEG),
            "image/gif" to animatedGif(), "image/webp" to picture(Bitmap.CompressFormat.WEBP))
        val sources = images.map { (type, bytes) -> inline(bytes, type) }
        val transport = FakeTransport { throw AssertionError("Inline pictures must not use the network") }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            sources.forEach { assertTrue(HomeworkImageSaver.supportedSource(it)) }
            assertTrue(sink.drafts.isEmpty())
            val result = saver.save(app, sources + sources.first())
            assertEquals(4, result.saved); assertEquals(0, result.failed); assertTrue(transport.requests.isEmpty())
            images.forEachIndexed { index, image ->
                assertEquals(image.first, sink.drafts[index].mime)
                assertArrayEquals(image.second, sink.drafts[index].bytes.toByteArray())
            }
        } finally { saver.close() }
    }

    @Test fun unsafeUrlsAndDocumentTargetsNeverReachTransportOrAlbum() {
        val invalid = listOf("http://p.ananas.chaoxing.com/a.png", "https://p.ananas.chaoxing.com:444/a.png",
            "https://user:password@p.ananas.chaoxing.com/a.png", "https://chaoxing.com.evil.invalid/a.png",
            "https://127.0.0.1/a.png", "file:///private/a.png", "content://private/a.png", "javascript:save()",
            "$first#private", "https://p.ananas.chaoxing.com/a.pdf", "https://d1.cldisk.com/file?filename=a.docx",
            "https://p.ananas.chaoxing.com\\@evil.invalid/a.png")
        val transport = FakeTransport { throw AssertionError("Unsafe URL reached transport") }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            invalid.forEach { assertNull(HomeworkImageSaver.imageUrl(it)) }
            val result = saver.save(app, invalid)
            assertEquals(invalid.size, result.failed); assertEquals(0, result.saved)
            assertEquals((1..invalid.size).toList(), result.failures.map { it.number })
            assertTrue(transport.requests.isEmpty()); assertTrue(sink.drafts.isEmpty())
        } finally { saver.close() }
    }

    @Test fun cookieSnapshotIsAccountAndPathMatchedAndNeverSentToExternalImageCdn() {
        val png = picture()
        val cookies = mutableListOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build(),
            Cookie.Builder().name("wrong_path").value("private").domain("chaoxing.com").path("/unrelated").secure().build(),
            Cookie.Builder().name("expired").value("old").domain("chaoxing.com").path("/").expiresAt(1).build(),
            Cookie.Builder().name("external").value("private").domain("cldisk.com").path("/").build())
        val transport = FakeTransport {
            if (it.url.toString() == first) response(it, byteArrayOf(), code = 302,
                headers = mapOf("Location" to "https://d1.cldisk.com/synthetic.png", "Set-Cookie" to "_uid=999; Domain=chaoxing.com; Path=/"))
            else response(it, png)
        }
        val sink = FakeSink(); val saver = isolatedSaver(transport, sink, cookies)
        cookies.clear()
        try {
            assertEquals(2, saver.save(app, listOf(first, second)).saved)
            assertEquals(listOf("_uid=901", null, "_uid=901"), transport.requests.map { it.header("Cookie") })
            assertTrue(transport.requests.all { it.method == "GET" && it.header("Authorization") == null })
        } finally { saver.close() }
    }

    @Test fun redirectsAreRevalidatedAndUntrustedOrAttachmentDestinationsAreNotFetched() {
        for (destination in listOf("https://evil.invalid/a.png", "http://p.ananas.chaoxing.com/a.png",
            "https://user:secret@p.ananas.chaoxing.com/a.png", "https://d1.cldisk.com/a.pdf")) {
            val transport = FakeTransport { response(it, byteArrayOf(), code = 302, headers = mapOf("Location" to destination)) }
            val sink = FakeSink(); val saver = isolatedSaver(transport, sink)
            try {
                assertEquals(1, saver.save(app, listOf(first)).failed)
                assertEquals(1, transport.requests.size); assertTrue(sink.drafts.isEmpty())
            } finally { saver.close() }
        }
        val loop = FakeTransport { response(it, byteArrayOf(), code = 302, headers = mapOf("Location" to first)) }
        val sink = FakeSink(); val saver = isolatedSaver(loop, sink)
        try { assertEquals(1, saver.save(app, listOf(first)).failed); assertEquals(4, loop.requests.size) }
        finally { saver.close() }
    }

    @Test fun htmlDocumentsDisguisedPicturesAndIncompleteFilesNeverCreateAlbumRows() {
        val png = picture()
        data class Case(val mime: String, val bytes: ByteArray, val headers: Map<String, String> = emptyMap())
        val cases = listOf(Case("text/html", "<html>login</html>".toByteArray()), Case("application/pdf", "%PDF-synthetic".toByteArray()),
            Case("image/svg+xml", "<svg/>".toByteArray()), Case("image/png", "<html>not a photo</html>".toByteArray()),
            Case("image/jpeg", png), Case("image/png", png.copyOf(png.size - 4)),
            Case("image/png", png, mapOf("Content-Length" to (png.size + 1).toString())))
        val transport = FakeTransport { request ->
            val index = request.url.queryParameter("case")!!.toInt(); val c = cases[index]
            response(request, c.bytes, c.mime, headers = c.headers)
        }
        val sink = FakeSink(); val saver = isolatedSaver(transport, sink)
        try {
            val result = saver.save(app, cases.indices.map { "$first?case=$it" })
            assertEquals(cases.size, result.failed); assertEquals(0, result.saved); assertTrue(sink.drafts.isEmpty())
        } finally { saver.close() }
    }

    @Test fun attachmentDispositionAllowsRealQuestionImagesWhileNonImagePayloadsStayRejected() {
        val png = picture(); val jpeg = picture(Bitmap.CompressFormat.JPEG)
        val replies = listOf("image/png" to png, "image/jpeg" to jpeg,
            "application/pdf" to "%PDF-synthetic-document".toByteArray(), "image/png" to "%PDF-disguised-document".toByteArray())
        val transport = FakeTransport { request ->
            val index = request.url.queryParameter("case")!!.toInt()
            response(request, replies[index].second, replies[index].first,
                headers = mapOf("Content-Disposition" to "attachment; filename=original-$index.bin"))
        }
        val sink = FakeSink(); val saver = isolatedSaver(transport, sink)
        try {
            val result = saver.save(app, replies.indices.map { "$first?case=$it" })
            assertEquals(2, result.saved); assertEquals(listOf(3, 4), result.failures.map { it.number })
            assertEquals(listOf("image/png", "image/jpeg"), sink.drafts.map { it.mime })
            assertArrayEquals(png, sink.drafts[0].bytes.toByteArray()); assertArrayEquals(jpeg, sink.drafts[1].bytes.toByteArray())
        } finally { saver.close() }
    }

    @Suppress("DEPRECATION")
    @Test fun genericBinaryMimeAcceptsVerifiedRasterImagesButRejectsHtmlJsonAndDocuments() {
        val images = listOf("image/png" to picture(), "image/jpeg" to picture(Bitmap.CompressFormat.JPEG),
            "image/gif" to animatedGif(), "image/webp" to picture(Bitmap.CompressFormat.WEBP))
        val invalid = listOf("<html>login</html>", "{\"document\":true}", "%PDF-synthetic", "PK\u0003\u0004archive")
            .map { it.toByteArray() }
        val payloads = images.map { it.second } + invalid
        val transport = FakeTransport { request ->
            val index = request.url.queryParameter("case")!!.toInt()
            response(request, payloads[index], "application/octet-stream",
                headers = mapOf("Content-Disposition" to "attachment; filename=original.bin"))
        }
        val sink = FakeSink(); val saver = isolatedSaver(transport, sink)
        try {
            val result = saver.save(app, payloads.indices.map { "$first?case=$it" })
            assertEquals(4, result.saved); assertEquals(listOf(5, 6, 7, 8), result.failures.map { it.number })
            images.forEachIndexed { index, image ->
                assertEquals(image.first, sink.drafts[index].mime)
                assertArrayEquals(image.second, sink.drafts[index].bytes.toByteArray())
            }
        } finally { saver.close() }
    }

    private class CountedInput(private val length: Int) : InputStream() {
        var readBytes = 0
        var closed = false
        override fun read(): Int = if (readBytes < length) { readBytes++; 0 } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = minOf(length - readBytes, len)
            if (count == 0) return -1
            b.fill(0, off, off + count); readBytes += count
            return count
        }
        override fun close() { closed = true }
    }
    private fun body(input: InputStream, length: Long): ResponseBody = object : ResponseBody() {
        private val buffered = input.source().buffer()
        override fun contentType(): MediaType = "image/png".toMediaType()
        override fun contentLength() = length
        override fun source(): BufferedSource = buffered
    }
    @Test fun advertisedAndStreamingByteLimitsStopBeforeAlbumWritesAndCloseBodies() {
        for (advertised in listOf(HomeworkImageSaver.MAX_BYTES + 1L, -1L)) {
            val input = CountedInput(HomeworkImageSaver.MAX_BYTES + 65536)
            val transport = FakeTransport { response(it, byteArrayOf(), body = body(input, advertised)) }
            val sink = FakeSink(); val saver = isolatedSaver(transport, sink)
            try {
                assertEquals(1, saver.save(app, listOf(first)).failed); assertTrue(sink.drafts.isEmpty())
                if (advertised > 0) assertEquals(0, input.readBytes)
                else assertTrue(input.readBytes <= HomeworkImageSaver.MAX_BYTES + 32768)
                assertTrue(input.closed)
            } finally { saver.close() }
        }
    }

    @Test fun unsupportedMalformedAndOversizedInlineImagesAreReportedWithoutNetworkOrAlbumChanges() {
        val oversized = "data:image/png;base64," + "A".repeat(((HomeworkImageSaver.MAX_BYTES + 2) / 3) * 4 + 4)
        val sources = listOf("data:image/svg+xml;base64,PHN2Zy8+", "data:image/png;base64,%%%", "data:image/png;base64,",
            inline("<html/>".toByteArray()), oversized)
        val transport = FakeTransport { throw AssertionError("Inline image used network") }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            val result = saver.save(app, sources)
            assertEquals(sources.size, result.failed); assertTrue(sink.drafts.isEmpty()); assertTrue(transport.requests.isEmpty())
        } finally { saver.close() }
    }

    @Test fun excessUniqueImageCountIsAnExplicitErrorBeforeAnyDownloadRatherThanSilentTruncation() {
        val transport = FakeTransport { throw AssertionError("Oversized batch started") }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            val failure = assertThrows(ApiProblem::class.java) { saver.save(app, (1..301).map { "$first?image=$it" }) }
            assertTrue(failure.message.orEmpty().contains("300")); assertTrue(transport.requests.isEmpty()); assertTrue(sink.drafts.isEmpty())
            val boundary = saver.save(app, (1..300).map { "https://unsupported.invalid/$it.png" })
            assertEquals(300, boundary.total); assertEquals(300, boundary.failed)
            assertTrue(transport.requests.isEmpty()); assertTrue(sink.drafts.isEmpty())
        } finally { saver.close() }
    }

    @Test fun cancellationAndAccountChangeDuringWriteRollBackBeforePublication() {
        for (changeAccount in listOf(false, true)) {
            var current = true
            val png = picture(); val transport = FakeTransport { response(it, png) }; val sink = FakeSink()
            val saver = isolatedSaver(transport, sink, guard = { if (!current) throw CancellationException() })
            sink.onWrite = { if (changeAccount) current = false else saver.cancel() }
            try {
                assertThrows(CancellationException::class.java) { saver.save(app, listOf(first, second)) }
                assertEquals(1, transport.requests.size); assertEquals(1, sink.drafts.size)
                assertTrue(sink.drafts.single().rolledBack); assertFalse(sink.drafts.single().published)
                assertTrue(sink.drafts.single().streamClosed)
            } finally { saver.close() }
        }
    }

    @Test fun cancelledTransportIOExceptionRemainsCancellationAndDoesNotStartTheNextImage() {
        lateinit var saver: HomeworkImageSaver
        val transport = FakeTransport { saver.cancel(); throw IOException("synthetic cancelled transfer") }; val sink = FakeSink()
        saver = isolatedSaver(transport, sink)
        try {
            assertThrows(CancellationException::class.java) { saver.save(app, listOf(first, second)) }
            assertEquals(1, transport.cancellations); assertEquals(1, transport.requests.size); assertTrue(sink.drafts.isEmpty())
        } finally { saver.close() }
    }

    @Test fun aCancelledSaverCanHandleANewExplicitBatchButAClosedSaverCannot() {
        val png = picture(); val transport = FakeTransport { response(it, png) }; val sink = FakeSink()
        val saver = isolatedSaver(transport, sink)
        try {
            sink.onWrite = { saver.cancel() }
            assertThrows(CancellationException::class.java) { saver.save(app, listOf(first)) }
            sink.onWrite = {}
            assertEquals(1, saver.save(app, listOf(second)).saved)
            assertTrue(sink.drafts.first().rolledBack); assertTrue(sink.drafts.last().published)
            saver.close()
            assertThrows(CancellationException::class.java) { saver.save(app, listOf(first)) }
            assertEquals(2, transport.requests.size)
        } finally { saver.close() }
    }

    @Test fun aFailedAlbumTransactionDoesNotBlockOtherPicturesOrRenumberTheirReferences() {
        val png = picture(); val transport = FakeTransport { response(it, png) }; val sink = FakeSink().apply { failPublish = setOf(1) }
        val saver = isolatedSaver(transport, sink)
        try {
            val result = saver.save(app, listOf(first, second))
            assertEquals(1, result.saved); assertEquals(1, result.failed); assertEquals(1, result.failures.single().number)
            assertTrue(sink.drafts[0].rolledBack); assertTrue(sink.drafts[1].published)
            assertTrue(sink.drafts[1].name.endsWith("-002.png"))
        } finally { saver.close() }
    }

    private class AlbumProvider(private val folder: File, val failPublish: Boolean = false, val failInsert: Boolean = false) : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val updates = mutableListOf<ContentValues>()
        val rows = linkedMapOf<Uri, File>()
        var deleted = 0
        var legacyBytesAtInsert = -1L
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val copy = ContentValues(values!!); inserted += copy
            val existing = copy.getAsString(MediaStore.Images.Media.DATA)?.let(::File)
            if (existing != null) legacyBytesAtInsert = existing.length()
            if (failInsert) return null
            val target = existing ?: File.createTempFile("synthetic-album-", ".img", folder)
            return Uri.withAppendedPath(uri, inserted.size.toString()).also { rows[it] = target }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(rows.getValue(uri),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates += ContentValues(values!!)
            return if (!failPublish && uri in rows) 1 else 0
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted++; rows.remove(uri)?.delete(); return 1
        }
        fun cleanup() { folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }
    private fun album(failPublish: Boolean = false, failInsert: Boolean = false): AlbumProvider {
        val folder = File(app.cacheDir, "synthetic-homework-album-${UUID.randomUUID()}").apply { mkdirs() }
        return AlbumProvider(folder, failPublish, failInsert).also {
            it.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", it)
        }
    }

    @Test fun modernMediaStoreUsesPendingPrivateDraftThenPublishesUnmodifiedImageWithoutStoragePermissions() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        val provider = album(); val png = picture(); val transport = FakeTransport { response(it, png) }
        val saver = HomeworkImageSaver(emptyList(), {}, transport)
        try {
            assertFalse(HomeworkImageSaver.requiresLegacyPermission(app))
            assertEquals(1, saver.save(app, listOf(first)).saved)
            assertEquals(1, provider.inserted.single().getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
            assertEquals("Pictures/ClassBell", provider.inserted.single().getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(0, provider.updates.single().getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
            assertArrayEquals(png, provider.rows.values.single().readBytes()); assertEquals(0, provider.deleted)
        } finally { saver.close(); provider.cleanup() }
    }

    @Test fun modernMediaStorePublishFailureDeletesThePendingRowAndItsBytes() {
        val provider = album(failPublish = true); val png = picture(); val transport = FakeTransport { response(it, png) }
        val saver = HomeworkImageSaver(emptyList(), {}, transport)
        try {
            assertEquals(1, saver.save(app, listOf(first)).failed)
            assertEquals(1, provider.deleted); assertTrue(provider.rows.isEmpty())
        } finally { saver.close(); provider.cleanup() }
    }

    @Test @Config(sdk = [28])
    fun legacyPermissionIsCheckedOnlyWhenTheUserSavesAndBeforeFetchingAnything() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        val transport = FakeTransport { throw AssertionError("Permission denial must precede image download") }
        val saver = HomeworkImageSaver(emptyList(), {}, transport)
        try {
            assertTrue(HomeworkImageSaver.requiresLegacyPermission(app)); assertTrue(transport.requests.isEmpty())
            assertEquals(0, saver.save(app, emptyList()).saved)
            assertThrows(SecurityException::class.java) { saver.save(app, listOf(first)) }
            assertTrue(transport.requests.isEmpty())
        } finally { saver.close() }
    }

    @Test @Config(sdk = [28])
    fun legacyAlbumWritesHiddenDraftBeforeRegistrationAndNeedsOnlyWritePermission() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val provider = album(); val folder = File(app.cacheDir, "synthetic-homework-legacy-${UUID.randomUUID()}")
        val png = picture(); val transport = FakeTransport { response(it, png) }
        val saver = HomeworkImageSaver(emptyList(), {}, transport) { HomeworkMediaStoreSink(it, folder) }
        try {
            assertEquals(1, saver.save(app, listOf(first)).saved)
            assertEquals(png.size.toLong(), provider.legacyBytesAtInsert)
            assertFalse(provider.inserted.single().containsKey(MediaStore.Images.Media.IS_PENDING))
            assertTrue(provider.updates.isEmpty()); assertEquals(1, folder.listFiles().orEmpty().size)
            assertArrayEquals(png, folder.listFiles()!!.single().readBytes())
        } finally { saver.close(); provider.cleanup(); folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }

    @Test @Config(sdk = [28])
    fun legacyRegistrationFailureRemovesBothHiddenDraftAndCompletedFile() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val provider = album(failInsert = true); val folder = File(app.cacheDir, "synthetic-homework-legacy-${UUID.randomUUID()}")
        val png = picture(); val transport = FakeTransport { response(it, png) }
        val saver = HomeworkImageSaver(emptyList(), {}, transport) { HomeworkMediaStoreSink(it, folder) }
        try {
            assertEquals(1, saver.save(app, listOf(first)).failed)
            assertEquals(png.size.toLong(), provider.legacyBytesAtInsert); assertTrue(folder.listFiles().orEmpty().isEmpty())
            assertTrue(provider.rows.isEmpty())
        } finally { saver.close(); provider.cleanup(); folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }
}
