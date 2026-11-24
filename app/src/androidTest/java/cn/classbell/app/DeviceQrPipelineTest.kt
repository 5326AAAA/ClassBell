package cn.classbell.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Physical-device tests. Synthetic accounts, private preferences, loopback TLS only. */
@RunWith(AndroidJUnit4::class)
class DeviceQrPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val item = SignActivity("301", "设备本地测试", Course("101", "201", "设备合成课程"), otherId=2)
    private fun qr(enc: String = "ABCDEF0123456789") = checkNotNull(SignQr.parse(
        "https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=$enc"))

    private inline fun isolated(block: (SignLedger) -> Unit) {
        val realSettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val before = realSettings.all.toMap()
        val name = "codex-device-qr-fixture-${UUID.randomUUID()}"
        try { block(SignLedger(context.getSharedPreferences(name, Context.MODE_PRIVATE))) }
        finally {
            context.deleteSharedPreferences(name)
            assertTrue("Device tests changed real application settings", before == realSettings.all)
        }
    }

    private data class Arrival(val uid: String, val enc: String, val at: Long)
    private inner class Fixture(val dynamic: Boolean = true, simultaneous: Int = 0) : AutoCloseable {
        val arrivals = ConcurrentLinkedQueue<Arrival>()
        val paths = ConcurrentLinkedQueue<String>()
        val bindings = AtomicInteger()
        val expireOld = AtomicBoolean()
        val failBoundPreparation = AtomicBoolean()
        val unknownFinal = AtomicBoolean()
        private val barrier = CountDownLatch(simultaneous)
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val server = MockWebServer()
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(4500, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .eventListenerFactory(DiagnosticLog.httpFactory("DeviceQrFixture")).build()

        init {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = checkNotNull(request.requestUrl)
                    val uid = checkNotNull(request.getHeader("X-Fixture-Uid"))
                    paths += url.encodedPath
                    val body = when (url.encodedPath) {
                        "/v2/apis/active/student/activelist" -> """{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":2,"status":1}]}}"""
                        "/v2/apis/active/getPPTActiveInfo" -> detail()
                        "/apis/login/userLogin4Uname.do" -> JSONObject().put("msg", JSONObject()
                            .put("uid", uid).put("name", "合成学生").put("fid", "101")).toString()
                        "/newsign/preSign" -> {
                            url.queryParameter("rcode")?.let {
                                check(it.startsWith("SIGNIN:aid=301&source=15&Code=123456&enc="))
                                bindings.incrementAndGet()
                            }
                            "<html>fixture preparation</html>"
                        }
                        "/pptSign/stuSignajax" -> {
                            check(url.queryParameter("uid") == uid)
                            check(url.queryParameter("activeId") == item.id)
                            check(url.queryParameter("signCode") == null)
                            val enc = checkNotNull(url.queryParameter("enc"))
                            arrivals += Arrival(uid, enc, SystemClock.elapsedRealtime())
                            barrier.countDown()
                            check(barrier.await(3, TimeUnit.SECONDS)) { "Account requests did not overlap" }
                            when {
                                expireOld.get() && enc == "11111111" -> """{"status":false,"mes":"二维码已失效，请重新扫码"}"""
                                unknownFinal.get() -> "fixture unknown response"
                                else -> "success"
                            }
                        }
                        else -> error("Unexpected test-only path ${url.encodedPath}")
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        private fun detail() = JSONObject().put("result", 1).put("data", JSONObject()
            .put("id", 301).put("courseId", 101).put("clazzid", 201).put("activeType", 2)
            .put("status", 1).put("otherId", 2).put("ifphoto", 0).put("configJson", "{}")
            .put("ifNeedVCode", 0).put("showVCode", 0).put("ifopenAddress", 0)
            .put("openCheckFaceFlag", 0).put("openCheckWeChatFlag", 0).put("openPreventCheatFlag", 0)
            .put("openSignOutFlag", 0).put("ifrefreshewm", if (dynamic) 1 else 0).put("ewmRefreshTime", 5)).toString()

        fun source(uid: String) = SigningApi(listOf(Cookie.Builder().name("_uid").value(uid)
            .domain("chaoxing.com").path("/").secure().build()), {}, allowQr=true, exchange={ official: Request ->
            if (official.url.encodedPath == "/newsign/preSign" && official.url.queryParameter("rcode") != null &&
                failBoundPreparation.compareAndSet(true, false)) throw IOException("fixture preparation interruption")
            // The production API validates its request before this exchange. Every test socket
            // connects to 127.0.0.1 with a certificate trusted only by this test client.
            val local = official.url.newBuilder().scheme("https").host("localhost").port(server.port).build()
            client.newCall(official.newBuilder().url(local).header("X-Fixture-Uid", uid).build()).execute().use {
                check(it.isSuccessful)
                checkNotNull(it.body).string()
            }
        })

        override fun close() {
            while (barrier.count > 0) barrier.countDown()
            client.dispatcher.cancelAll()
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun submit(ledger: SignLedger, source: SignSource, prepared: PreparedQr, material: SignQr,
                       owner: String = "device-fixture-owner") = SignSubmission(ledger).submit(owner, item, source,
        SignInputs(qr=material, expectedMode=SignMode.QR, preparedQr=prepared)) {}

    @Test fun decodedStaticQrImmediatelyUsesOnlyFinalRequest() = isolated { ledger ->
        Fixture(dynamic=false).use { fixture ->
            val source = fixture.source("901")
            try {
                val prepared = source.prepareQr(item)
                val payload = qr().officialUrl
                val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) -0x1000000 else -0x1 }
                bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
                val material = try { checkNotNull(SignQr.parse(QrImage.decodeSelected(bitmap, null))).also { it.requireFor(item) } }
                    finally { bitmap.recycle() }
                val before = fixture.paths.size
                val start = SystemClock.elapsedRealtime()
                assertTrue(submit(ledger, source, prepared, material).successful)
                val elapsed = SystemClock.elapsedRealtime() - start
                assertTrue("Static QR pipeline exceeded five seconds: $elapsed", elapsed < 5000)
                assertEquals(listOf("/pptSign/stuSignajax"), fixture.paths.toList().drop(before))
                reportDeviceEvidence("DEVICE_QR static_decoded_result_ms=$elapsed")
            } finally { source.cancel() }
        }
    }

    @Test fun expiredReplyAllowsFreshQrAndConfirmedAccountIsNotSubmittedAgain() = isolated { ledger ->
        Fixture().use { fixture ->
            fixture.expireOld.set(true)
            val source = fixture.source("901")
            try {
                val prepared = source.prepareQr(item)
                val expired = submit(ledger, source, prepared, qr("11111111"))
                assertTrue(expired.retryAllowed)
                assertFalse(ledger.attempted("device-fixture-owner", item))
                assertTrue(submit(ledger, source, prepared, qr("22222222")).successful)
                assertEquals(SignResultCategory.ALREADY_ATTEMPTED,
                    submit(ledger, source, prepared, qr("33333333")).category)
                assertEquals(listOf("11111111", "22222222"), fixture.arrivals.map { it.enc })
                reportDeviceEvidence("DEVICE_QR expired_then_fresh=true final_requests=2")
            } finally { source.cancel() }
        }
    }

    @Test fun tenPreparedAccountsReachLoopbackTlsConcurrentlyWithinFiveSeconds() = isolated { ledger ->
        Fixture(simultaneous=10).use { fixture ->
            val sources = (901..910).map { fixture.source(it.toString()) }
            val pool = Executors.newFixedThreadPool(10)
            try {
                val prepared = sources.map { it.prepareQr(item) }
                val material = qr()
                val start = SystemClock.elapsedRealtime()
                val completed = ConcurrentLinkedQueue<Long>()
                val tasks = sources.mapIndexed { index, source -> pool.submit<SignOutcome> {
                    submit(ledger, source, prepared[index], material, "device-fixture-$index")
                        .also { completed += SystemClock.elapsedRealtime() }
                } }
                assertTrue(tasks.map { it.get(5, TimeUnit.SECONDS) }.all { it.successful })
                assertEquals(10, fixture.arrivals.size)
                assertEquals((901..910).map { it.toString() }.toSet(), fixture.arrivals.map { it.uid }.toSet())
                assertEquals(10, fixture.bindings.get())
                val arrival = fixture.arrivals.maxOf { it.at - start }
                val result = completed.maxOf { it - start }
                assertTrue("Last account reached server after $arrival ms", arrival < 5000)
                assertTrue("Last account result completed after $result ms", result < 5000)
                reportDeviceEvidence("DEVICE_QR accounts=10 tls_arrival_max_ms=$arrival confirmed_result_max_ms=$result")
            } finally { pool.shutdownNow(); sources.forEach { it.cancel() } }
        }
    }

    @Test fun locallyStaleMaterialDoesNotReserveOrSendAndFreshCodeCanContinue() = isolated { ledger ->
        Fixture().use { fixture ->
            val source = fixture.source("901")
            try {
                val prepared = source.prepareQr(item)
                val stale = qr("11111111").apply { capturedAt -= 5001 }
                val rejected = submit(ledger, source, prepared, stale)
                assertTrue(rejected.retryAllowed)
                assertFalse(ledger.attempted("device-fixture-owner", item))
                assertTrue(fixture.arrivals.isEmpty())
                assertTrue(submit(ledger, source, prepared, qr("22222222")).successful)
            } finally { source.cancel() }
        }
    }

    @Test fun interruptedPreparationCanContinueWithFreshCode() = isolated { ledger ->
        Fixture().use { fixture ->
            val source = fixture.source("901")
            try {
                val prepared = source.prepareQr(item)
                fixture.failBoundPreparation.set(true)
                val result = submit(ledger, source, prepared, qr("11111111"))
                assertEquals(SignResultCategory.NOT_SUBMITTED, result.category)
                assertTrue(result.retryAllowed)
                assertFalse(ledger.attempted("device-fixture-owner", item))
                assertTrue(fixture.arrivals.isEmpty())
                assertTrue(submit(ledger, source, prepared, qr("22222222")).successful)
            } finally { source.cancel() }
        }
    }

    @Test fun unrecognizedFinalReplyStaysUncertainInsteadOfBlindResubmission() = isolated { ledger ->
        Fixture().use { fixture ->
            fixture.unknownFinal.set(true)
            val source = fixture.source("901")
            try {
                val prepared = source.prepareQr(item)
                val result = submit(ledger, source, prepared, qr("11111111"))
                assertEquals(SignResultCategory.UNKNOWN, result.category)
                assertFalse(result.retryAllowed)
                assertEquals(SignResultCategory.ALREADY_ATTEMPTED,
                    submit(ledger, source, prepared, qr("22222222")).category)
                assertEquals(1, fixture.arrivals.size)
            } finally { source.cancel() }
        }
    }
}
