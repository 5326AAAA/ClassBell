package cn.classbell.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=BellApp::class, qualifiers="zh-rCN-w390dp-h844dp-mdpi",
    shadows=[HostFileProviderPaths::class], instrumentedPackages=["androidx.core.content"])
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticLogTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    @Before fun reset() { DiagnosticLog.init(app); DiagnosticLog.clear() }

    @Test fun removingAccountPhotoDoesNotLogAccountLabel() {
        val button=Button(app).apply{text="移除 合成私人账号 的本次照片";setLoggedClickListener{}}
        button.performClick()
        val log=DiagnosticLog.snapshot()
        assertTrue(log.contains("移除本次照片"));assertFalse(log.contains("合成私人账号"))
    }

    @Test fun credentialsAndExceptionPayloadsNeverReachDisk() {
        DiagnosticLog.event("Test", "password=secret-A cookie=secret-B token=secret-C phone=13912345678 https://example.invalid/work?password=secret-D#secret-E")
        DiagnosticLog.error("Test", IOException("Cookie: secret-F", IllegalStateException("secret-G")))
        DiagnosticLog.event("Test", "Authorization: Bearer private-bearer\nCookie: a=private-cookie; b=private-second")
        DiagnosticLog.event("Test", "\"password\":\"private spaced password\", token='private quoted token'")
        val log=DiagnosticLog.snapshot()
        for(secret in listOf("secret-A","secret-B","secret-C","secret-D","secret-E","secret-F","secret-G","13912345678")) assertFalse(secret,log.contains(secret))
        assertTrue(log.contains("IOException"));assertTrue(log.contains("IllegalStateException"));assertTrue(log.contains("DiagnosticLogTest.kt"))
        assertFalse(DiagnosticLog.endpoint("https://user:secret@x.invalid/work/12345?token=secret").contains("secret"))
        for(secret in listOf("private-bearer","private-cookie","private-second","private spaced password","private quoted token"))assertFalse(log.contains(secret))
    }
    @Test fun scopedFailuresKeepCorrelationAndRestoreParentScope() {
        assertThrows(IOException::class.java) { DiagnosticLog.operation("HomeworkFixture") {
            DiagnosticLog.event("CourseFixture","before_failure");throw IOException("private body")
        } }
        DiagnosticLog.event("AfterFixture","finished")
        val log=DiagnosticLog.snapshot()
        val operation=Regex("\\[op=([^]]+)\\].*begin").find(log)!!.groupValues[1]
        assertTrue(log.lines().first{it.contains("before_failure")}.contains("op=$operation"))
        assertTrue(log.lines().first{it.contains("AfterFixture")}.contains("op=-"))
        assertFalse(log.contains("private body"))
    }
    @Test fun concurrentWritersProduceWholeLinesAndRetainAcrossInitialization() {
        val pool=Executors.newFixedThreadPool(4)
        repeat(4){worker->pool.submit{repeat(40){DiagnosticLog.event("Concurrent","worker=$worker row=$it marker_end")}}}
        pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))
        val first=DiagnosticLog.snapshot();assertEquals(160,first.lines().count{it.endsWith("marker_end")})
        DiagnosticLog.init(app)
        assertTrue(DiagnosticLog.snapshot().contains("worker=3 row=39 marker_end"))
    }
    @Test fun rotationBoundsDiskAndKeepsNewestRecords() {
        repeat(12){batch->repeat(160){DiagnosticLog.event("Rotation","batch=$batch "+"x".repeat(5000))};DiagnosticLog.snapshot()}
        DiagnosticLog.event("Rotation","newest_marker")
        val log=DiagnosticLog.snapshot();val dir=File(app.filesDir,"diagnostics")
        assertTrue(log.contains("newest_marker"));assertFalse(log.contains("batch=0"))
        assertTrue(dir.listFiles()!!.size<=4)
        assertTrue(dir.listFiles()!!.sumOf{it.length()}<=4L*DiagnosticLog.SEGMENT_BYTES)
    }
    @Test fun httpStatusAndNetworkFailuresAreRecordedWithoutHeadersOrBody() {
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).apply{soTimeout=5000}
        val peer=Thread{
            server.use{it.accept().use{socket->
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader()
                while(!input.readLine().isNullOrEmpty()) { /* discard request, including credentials */ }
                socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 16\r\nConnection: close\r\n\r\nprivate-response".toByteArray())
            }}
        }.apply{start()}
        val transport=OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("HttpFixture")).callTimeout(5,TimeUnit.SECONDS).build()
        try {transport.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/work?token=secret-query").header("Cookie","secret-cookie").build()).execute().use{it.body!!.string()}}
        finally {server.close();peer.join(6000)}
        val failing=transport.newBuilder().apply{interceptors().clear()}.addInterceptor{throw java.net.SocketTimeoutException("secret-url")}.build()
        assertThrows(IOException::class.java){failing.newCall(Request.Builder().url("https://example.invalid/work").build()).execute()}
        val log=DiagnosticLog.snapshot()
        assertTrue(log.contains("headers http=403"));assertTrue(log.contains("body_end bytes=16"));assertTrue(log.contains("SocketTimeoutException"));assertTrue(log.contains("failed phase="));assertTrue(log.contains("cancelled=false reason=timeout"))
        for(secret in listOf("secret-query","secret-cookie","secret-url","private-response"))assertFalse(log.contains(secret))
        transport.dispatcher.executorService.shutdown();failing.dispatcher.executorService.shutdown()
    }
    @Test fun crashRecordIsSynchronousAndDelegatesToOriginalHandler() {
        val saved=Thread.getDefaultUncaughtExceptionHandler()
        var delegated=false
        try {
            Thread.setDefaultUncaughtExceptionHandler{_,_->delegated=true}
            DiagnosticLog.init(app)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(),IllegalStateException("private-crash-content"))
            assertTrue(delegated)
            val persisted=File(app.filesDir,"diagnostics/current.log").readText()
            assertTrue(persisted.contains("uncaught thread="));assertFalse(persisted.contains("private-crash-content"))
        } finally {Thread.setDefaultUncaughtExceptionHandler(saved)}
    }
    @Test fun partialHomeworkFailureExplainsSkippedDetailsAndPreservesOldDeadline() {
        val course=Course("101","201","private-course-name")
        val html="""<li data="https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial?courseId=101&amp;clazzId=201&amp;taskrefId=1"><div role=option><p>private-homework-title</p><span class=status>未提交</span></div></li>"""
        val transport=OkHttpClient.Builder().addInterceptor{chain->
            if(chain.request().url.encodedPath!="/work/stu-work")throw java.net.SocketTimeoutException("private-response")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val api=HomeworkApi(SessionCookies(app.vault,null),transport){1_000_000L}
        try {
            val old=HomeworkParser.parse(html,listOf(course),900_000L).let{it.copy(items=it.items.map{row->row.copy(dueAt=9_000_000L,sourceTime="fixture")})}
            val result=api.fetch(listOf(course),old)
            assertEquals(9_000_000L,result.items.single().dueAt)
            val log=DiagnosticLog.snapshot()
            assertTrue(log.contains("network_failure remaining_details_skipped=true"));assertTrue(log.contains("SocketTimeoutException"))
            assertTrue(log.contains("course_ref=${DiagnosticLog.reference(course.key)}"))
            assertTrue(log.contains("private-homework-title"));assertTrue(log.contains("private-course-name"));assertFalse(log.contains("private-response"))
        } finally {api.cancel();transport.dispatcher.executorService.shutdown()}
    }
    @Test fun malformedHomeworkPageIsLoggedAsFailureNotEmptySuccess() {
        val transport=OkHttpClient.Builder().addInterceptor{chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("<html>private-response</html>".toResponseBody("text/html".toMediaType())).build()}.build()
        val api=HomeworkApi(SessionCookies(app.vault,null),transport)
        try { assertThrows(ApiProblem::class.java){api.fetch(emptyList())}
            val log=DiagnosticLog.snapshot();assertTrue(log.contains("HomeworkParse"));assertTrue(log.contains("ApiProblem"));assertFalse(log.contains("private-response"))
        } finally{api.cancel();transport.dispatcher.executorService.shutdown()}
    }
    @Test fun exportedFileIsUtf8AndReadOnlyUriGrantCannotExposeVault() {
        val text="中文日志\nsecond line";val file=LogSharing.createFile(app,text)
        val intent=LogSharing.intent(app,file)
        assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("text/plain",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri=intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content",uri.scheme);assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        assertEquals(text,app.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()})
        val privateFile=File(app.filesDir,"private-vault-fixture").apply{writeText("secret")}
        assertThrows(IllegalArgumentException::class.java){FileProvider.getUriForFile(app,"${app.packageName}.diagnostic-files",privateFile)}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun diagnosticScreenExportsToWechatAndRenders() {
        DiagnosticLog.event("UiFixture","ready")
        val controller=Robolectric.buildActivity(DiagnosticActivity::class.java).setup()
        try {
            val activity=controller.get();val root=activity.window.decorView
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
            fun await(check:()->Boolean){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};fail("UI timed out")}
            await{views(root).filterIsInstance<TextView>().any{it.text.contains("UiFixture")}}
            val width=390;val height=844
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
            File("../outputs/运行日志页面-0.8.9.png").apply{parentFile.mkdirs();outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
            views(root).filterIsInstance<Button>().single{it.text=="分享日志到微信"}.performClick()
            await{views(root).filterIsInstance<TextView>().any{it.text.contains("日志已导出")}}
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals("com.tencent.mm",intent.`package`);assertEquals(Intent.ACTION_SEND,intent.action)
            assertNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        } finally {controller.pause().stop().destroy()}
    }
}

/** AndroidX uses literal '/' in its root boundary check. Normalize only that check for the Windows JVM.
 * XML root parsing, URI encoding, canonicalization, provider permissions and file reads remain real.
 * This test-only class is not packaged in the APK. */
@org.robolectric.annotation.Implements(className="androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk=false)
class HostFileProviderPaths {
    @org.robolectric.annotation.Implementation
    fun belongsToRoot(path: String, rootPath: String): Boolean {
        val pathValue=path.replace(File.separatorChar,'/').trimEnd('/')
        val rootValue=rootPath.replace(File.separatorChar,'/').trimEnd('/')
        return pathValue!=rootValue && pathValue.startsWith("$rootValue/")
    }
}
