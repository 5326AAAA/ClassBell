package cn.classbell.app

import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback HTTP transport with synthetic accounts; no request reaches Chaoxing or a school. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class QrTransportTimingTest {
    private val item=SignActivity("301","本地传输计时",Course("101","201","合成课程"),otherId=2)
    private fun material(enc:String="ABCDEF0123456789")=checkNotNull(SignQr.parse(
        "https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=$enc"))
    private fun ledger(name:String):SignLedger {
        val preferences=RuntimeEnvironment.getApplication().getSharedPreferences("qr-http-$name",0)
        preferences.edit().clear().commit()
        return SignLedger(preferences)
    }

    private data class Arrival(val uid:String,val enc:String,val nanos:Long)

    private inner class LoopbackFixture(expectedConcurrent:Int=0):AutoCloseable {
        val arrivals=ConcurrentLinkedQueue<Arrival>()
        val boundRcodes=ConcurrentLinkedQueue<String>()
        val errors=ConcurrentLinkedQueue<Throwable>()
        val expireOld=AtomicBoolean(false)
        private val concurrentlySending=CountDownLatch(expectedConcurrent)
        private val serverWorkers=Executors.newCachedThreadPool()
        private val acceptor=Executors.newSingleThreadExecutor()
        private val running=AtomicBoolean(true)
        private val activeSockets=ConcurrentHashMap.newKeySet<Socket>()
        private val server=ServerSocket(0,50,InetAddress.getByName("127.0.0.1")).apply{soTimeout=250}
        private val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(1,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS)
            .callTimeout(4_500,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .eventListenerFactory(DiagnosticLog.httpFactory("FixtureTransport")).build()

        init {
            acceptor.submit {
                while(running.get()) {
                    try {
                        val socket=server.accept()
                        activeSockets+=socket
                        serverWorkers.submit{handle(socket)}
                    } catch(_:SocketTimeoutException) {
                        // Periodically observe closure while the fixture is idle.
                    } catch(error:SocketException) {
                        if(running.get())errors+=error
                        break
                    }
                }
            }
        }

        private fun handle(socket:Socket) {
            socket.use {
                try {
                    socket.soTimeout=4_000
                    val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine=checkNotNull(input.readLine()).split(' ',limit=3)
                    check(requestLine.size==3 && requestLine[0]=="GET")
                    val headers=mutableMapOf<String,String>()
                    while(true) {
                        val line=checkNotNull(input.readLine())
                        if(line.isEmpty())break
                        val colon=line.indexOf(':')
                        check(colon>0)
                        headers[line.substring(0,colon).trim().lowercase(Locale.ROOT)]=line.substring(colon+1).trim()
                    }
                    val uid:String=checkNotNull(headers["x-fixture-uid"])
                    val url=("http://127.0.0.1"+requestLine[1]).toHttpUrl()
                    val body=when(url.encodedPath) {
                        "/v2/apis/active/student/activelist"->"""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":2,"status":1}]}}"""
                        "/v2/apis/active/getPPTActiveInfo"->detail()
                        "/apis/login/userLogin4Uname.do"->JSONObject().put("msg",JSONObject().put("uid",uid)
                            .put("name","合成学生").put("fid","101")).toString()
                        "/newsign/preSign"->{
                            url.queryParameter("rcode")?.let{rcode->
                                check(rcode.startsWith("SIGNIN:aid=301&source=15&Code=123456&enc="))
                                boundRcodes+=rcode
                            }
                            "<html>本地准备完成</html>"
                        }
                        "/pptSign/stuSignajax"->{
                            check(uid==url.queryParameter("uid")) { "Account snapshot was mixed with another request" }
                            check(url.queryParameter("activeId")=="301")
                            check(url.queryParameter("signCode")==null)
                            val enc:String=checkNotNull(url.queryParameter("enc"))
                            arrivals+=Arrival(uid,enc,System.nanoTime())
                            concurrentlySending.countDown()
                            check(concurrentlySending.await(3,TimeUnit.SECONDS)) { "Independent account requests did not overlap" }
                            if(expireOld.get() && enc=="11111111")
                                """{"status":false,"mes":"二维码已失效，请重新扫码"}"""
                            else "success"
                        }
                        else->error("Unexpected loopback protocol path ${url.encodedPath}")
                    }
                    respond(socket,200,body)
                } catch(error:Throwable) {
                    errors+=error
                    runCatching{respond(socket,500,"fixture failure")}
                } finally {activeSockets.remove(socket)}
            }
        }

        private fun detail()=JSONObject().put("result",1).put("data",JSONObject()
            .put("id",301).put("courseId",101).put("clazzid",201).put("activeType",2).put("status",1)
            .put("otherId",2).put("ifphoto",0).put("configJson","{}")
            .put("ifNeedVCode",0).put("showVCode",0).put("ifopenAddress",0)
            .put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0).put("openPreventCheatFlag",0)
            .put("openSignOutFlag",0).put("ifrefreshewm",1).put("ewmRefreshTime",5)).toString()

        private fun respond(socket:Socket,status:Int,body:String) {
            val bytes=body.toByteArray(Charsets.UTF_8)
            val header="HTTP/1.1 $status ${if(status==200)"OK" else "Error"}\r\n"+
                "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply{
                write(header.toByteArray(Charsets.US_ASCII));write(bytes);flush()
            }
        }

        fun source(uid:String)=SigningApi(listOf(Cookie.Builder().name("_uid").value(uid)
            .domain("chaoxing.com").path("/").secure().build()),{},allowQr=true,exchange={official->
                // SigningApi first validates its official request. Only its host and scheme are
                // redirected for this fixture; path, encoded query, tags and method are preserved.
                val localUrl=official.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                val local=official.newBuilder().url(localUrl).header("X-Fixture-Uid",uid).build()
                client.newCall(local).execute().use{response->
                    check(response.isSuccessful) { "Loopback fixture returned HTTP ${response.code}" }
                    checkNotNull(response.body).string()
                }
            })

        override fun close() {
            while(concurrentlySending.count>0)concurrentlySending.countDown()
            running.set(false)
            client.dispatcher.cancelAll()
            server.close()
            activeSockets.forEach{runCatching{it.close()}}
            acceptor.shutdownNow()
            serverWorkers.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun tenIndependentPreparedAccountsCompleteRealLoopbackRequestsWithinFiveSeconds() {
        val fixture=LoopbackFixture(expectedConcurrent=10)
        val sources=(1..10).map{fixture.source((900+it).toString())}
        val executor=Executors.newFixedThreadPool(10)
        try {
            val prepared=sources.map{it.prepareQr(item)}
            assertTrue(fixture.arrivals.isEmpty())
            val ledger=ledger("ten-accounts")
            val material=material()
            val start=System.nanoTime()
            val completions=ConcurrentLinkedQueue<Long>()
            val tasks=sources.mapIndexed {index,api->executor.submit<SignOutcome>{
                SignSubmission(ledger).submit("account-$index",item,api,
                    SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared[index])){}
                    .also{completions+=System.nanoTime()}
            }}
            val outcomes=tasks.map{it.get(5,TimeUnit.SECONDS)}
            assertTrue("Fixture server failed: ${fixture.errors.map{it.javaClass.simpleName}}",fixture.errors.isEmpty())
            assertEquals(10,fixture.arrivals.size)
            assertEquals((901..910).map{it.toString()}.toSet(),fixture.arrivals.map{it.uid}.toSet())
            assertTrue(fixture.arrivals.all{it.enc==material.enc})
            assertEquals(10,fixture.boundRcodes.size)
            assertTrue(outcomes.all{it.successful})
            assertEquals(10,completions.size)
            val sendMax=fixture.arrivals.maxOf{TimeUnit.NANOSECONDS.toMillis(it.nanos-start)}
            val resultMax=completions.maxOf{TimeUnit.NANOSECONDS.toMillis(it-start)}
            assertTrue("Loopback arrival took ${sendMax}ms",sendMax<5_000)
            assertTrue("Loopback confirmed-result processing took ${resultMax}ms",resultMax<5_000)
            println("LOCAL_QR_HTTP accounts=10 arrival_max_ms=$sendMax confirmed_result_max_ms=$resultMax")
        } finally {executor.shutdownNow();sources.forEach{it.cancel()};fixture.close()}
    }

    @Test fun actualExpiredReplyReleasesReservationAndFreshCodeSucceedsWithoutRepeatingSuccess() {
        val fixture=LoopbackFixture()
        fixture.expireOld.set(true)
        val api=fixture.source("901")
        try {
            val prepared=api.prepareQr(item)
            val ledger=ledger("expired-then-fresh")
            val submission=SignSubmission(ledger)
            fun submit(enc:String)=submission.submit("owner",item,api,
                SignInputs(qr=material(enc),expectedMode=SignMode.QR,preparedQr=prepared)){}
            val expired=submit("11111111")
            assertFalse(expired.successful)
            assertTrue(expired.retryAllowed)
            assertFalse(ledger.attempted("owner",item))
            val start=System.nanoTime()
            assertTrue(submit("22222222").successful)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<5_000)
            assertTrue(ledger.attempted("owner",item))
            assertEquals(SignResultCategory.ALREADY_ATTEMPTED,submit("33333333").category)
            assertEquals(listOf("11111111","22222222"),fixture.arrivals.map{it.enc})
            assertTrue(fixture.errors.isEmpty())
        } finally {api.cancel();fixture.close()}
    }
}
