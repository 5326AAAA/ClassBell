package cn.classbell.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: real, read-only homework and user-requested image saves. Never submits answers. */
@RunWith(AndroidJUnit4::class)
class DeviceHomeworkLiveTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val app get() = instrumentation.targetContext.applicationContext as BellApp
    private fun views(root: View): List<View> = listOf(root) + if(root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun text(activity: Activity): String {
        var result = ""
        main { result = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return result
    }
    private fun await(message: String, timeout: Long = 90_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while(SystemClock.elapsedRealtime() < until) {
            instrumentation.waitForIdleSync()
            if(condition()) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun click(activity: Activity, label: String) = main {
        val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == label || it.contentDescription?.toString()==label }
        assertTrue("Disabled button: $label", button.isEnabled)
        button.performClick()
    }
    private fun clipboard(): String {
        var result = ""
        main { result = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
        return result
    }
    private fun albumNames(): Set<String> {
        val names = mutableSetOf<String>()
        app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH}=?", arrayOf("Pictures/ClassBell/"), null)?.use { cursor ->
            while(cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }
    private fun verifyAlbumImages(names: Set<String>) {
        var verified = 0
        app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.RELATIVE_PATH}=?", arrayOf("Pictures/ClassBell/"), null)?.use { cursor ->
            while(cursor.moveToNext()) {
                if(cursor.getString(1) !in names) continue
                assertEquals("Image remained pending", 0, cursor.getInt(2))
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                assertTrue("Saved image cannot be decoded", bounds.outWidth > 0 && bounds.outHeight > 0)
                verified++
            }
        }
        assertEquals("Saved image rows could not be read back", names.size, verified)
    }

    @Test fun realHomeworkReadCopyAndSave() {
        assumeTrue("Real account tests require explicit liveHomework=true", arguments.getString("liveHomework") == "true")
        val owner = app.prefs.getString("identity", "").orEmpty()
        assertTrue("Log in before the live read test", owner.isNotBlank() && app.prefs.getBoolean("loggedIn", false))
        val needle = String(Base64.decode(arguments.getString("needleBase64"), Base64.DEFAULT), Charsets.UTF_8)
        val snapshot = EncryptedHomeworkCache(app.vault).read(owner) ?: error("Refresh the homework list first")
        val item = snapshot.items.single { it.title == needle && !it.peerReview && !it.activityTask }
        fun guard() { check(app.prefs.getString("identity", "") == owner && app.prefs.getBoolean("loggedIn", false)) }
        val folder = File(app.filesDir, "codex-debug/homework-live").apply { mkdirs() }
        File(folder, "available.json").writeText(JSONArray(snapshot.items.filter { !it.peerReview && !it.activityTask }.map {
            JSONObject().put("title", it.title).put("course", it.courseName).put("state", it.state(System.currentTimeMillis()).name)
        }).toString(2))
        val beginApproved = arguments.getString("beginHomework") == "true"
        val report = JSONObject().put("runId",arguments.getString("runId")).put("version", BuildConfig.VERSION_NAME)
            .put("title",item.title).put("beginApproved",beginApproved).put("phase","loading")
        File(folder,"page.html").delete();File(folder,"private-page.json").delete();File(folder,"screen.png").delete()
        File(folder,"result.json").writeText(report.toString(2))
        var responseNumber=0
        val diagnosticTransport=if(arguments.getString("tracePaging")=="true")OkHttpClient.Builder().addInterceptor{chain->
            val response=chain.proceed(chain.request())
            val number=responseNumber++
            File(folder,"paging-$number.html").writeText(response.peekBody(2L*1024*1024).string())
            File(folder,"private-paging-$number.json").writeText(JSONObject().put("url",response.request.url.toString()).put("code",response.code).toString())
            response
        }.build() else null
        val page = try { HomeworkDetailSource(app.cookies, ::guard,diagnosticTransport).load(item, beginApproved) }
        catch(error:Exception) {
            report.put("phase","load_failed").put("error",error.javaClass.simpleName)
            File(folder,"result.json").writeText(report.toString(2));throw error
        }
        // Raw pages are app-private diagnostics, never test stdout or source fixtures.
        File(folder, "page.html").writeText(page.html)
        File(folder, "private-page.json").writeText(JSONObject().put("url", page.url).toString())
        val set = HomeworkQuestionParser.parse(page.html, page.url)
        report.put("phase","read").put("questions", set.questions.size).put("images", set.imageUrls.size)
            .put("skipped", set.skipped).put("unsupportedImages", set.unsupportedImages)
            .put("beginApproved", beginApproved)
        File(folder, "result.json").writeText(report.toString(2))
        println("LIVE_HOMEWORK_READ " + report)
        if(arguments.getString("probeImageHeaders") == "true") {
            val image=set.imageUrls.first().toHttpUrl()
            val origin=page.url.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
            val client=OkHttpClient.Builder().build()
            try {
                val cookies=app.cookies.snapshot().filter{it.matches(image)}.joinToString("; "){"${it.name}=${it.value}"}
                val request=Request.Builder().url(image)
                    .header("User-Agent","Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    .header("Referer",origin).header("Cookie",cookies).build()
                client.newCall(request).execute().use { response->
                    report.put("imageProbeCode",response.code).put("imageProbeType",response.header("Content-Type"))
                        .put("imageProbeBytes",response.body?.contentLength())
                }
            }finally{client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
            File(folder,"result.json").writeText(report.toString(2));println("LIVE_IMAGE_PROBE "+report)
            return
        }
        if(arguments.getString("mode") == "inspect") return
        assertTrue("The real page did not yield questions", set.questions.isNotEmpty())
        arguments.getString("expectedQuestions")?.toIntOrNull()?.let{expected->
            assertEquals("Whole-paper count must match the independently expected number",expected,set.questions.size)
            report.put("expectedQuestionsVerified",expected)
        }
        assertEquals("Unrecognized question blocks", 0, set.skipped)
        var activity: HomeworkExportActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(app, HomeworkExportActivity::class.java)
                .putExtra("owner", owner).putExtra("homework", item.json().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeworkExportActivity
            val current = activity
            if(beginApproved) {
                await("Native page did not show either questions or the begin notice") {
                    val value=text(current)
                    "题目已读取，图片尚未下载。" in value || "进入这份作业并读取题目" in value
                }
                if("进入这份作业并读取题目" in text(current))click(current,"进入这份作业并读取题目")
            }
            await("Native question page did not finish reading") { "题目已读取，图片尚未下载。" in text(current) }
            click(current, "复制全部题目文字")
            val all = clipboard()
            assertEquals(set.exportText(item.title, item.courseName), all)
            val pageLabels=org.jsoup.Jsoup.parse(page.html).select(".Answer span[orichoice]").map{it.attr("orichoice")}
                .filter{it.matches(Regex("[A-H]"))}
            if(pageLabels.isNotEmpty()) {
                val copiedLabels=Regex("(?m)^([A-H])\\.(?: |$)").findAll(all).map{it.groupValues[1]}.toList()
                assertEquals("Copied choices must match the independent HTML option labels",pageLabels,copiedLabels)
                report.put("optionLabelsVerified",pageLabels.size)
            }
            report.put("allCopyCharacters", all.length).put("allCopyVerified", true)
            main {
                val single = views(current.window.decorView).filterIsInstance<Button>().first { it.text.toString() == "复制本题文字" || it.contentDescription=="复制本题文字" }
                assertTrue(single.isEnabled); single.performClick()
            }
            assertEquals(set.exportText(item.title, item.courseName, set.questions.first()), clipboard())
            report.put("singleCopyVerified", true)
            if(arguments.getString("saveImages") == "true") {
                assertTrue("Select a real homework containing images", set.imageUrls.isNotEmpty())
                assertTrue("Keep the live save batch under 40 images", set.imageUrls.size <= 40)
                val before = albumNames()
                click(current, "保存全部题图到相册（${set.imageUrls.size} 张）")
                await("Image save did not complete", 180_000) { "保存完成：" in text(current) }
                val after = albumNames() - before
                assertTrue("Some real images failed to save: " + text(current),
                    "本次保存 ${set.imageUrls.size} / ${set.imageUrls.size} 张；失败 0 张。" in text(current))
                assertEquals(set.imageUrls.size, after.size)
                verifyAlbumImages(after)
                report.put("allSavedImages", after.size).put("albumVerified", true)
                val firstWithImages = set.questions.first { it.images.isNotEmpty() }
                val singleBefore = albumNames()
                main {
                    val single = views(current.window.decorView).filterIsInstance<Button>()
                        .first { it.text.toString().startsWith("保存本题图片") || it.contentDescription?.startsWith("保存本题图片")==true }
                    assertTrue(single.isEnabled); single.performClick()
                }
                await("Single-question images did not finish saving", 180_000) { "保存完成：" in text(current) }
                val singleAfter = albumNames() - singleBefore
                assertEquals(firstWithImages.images.distinct().size, singleAfter.size)
                verifyAlbumImages(singleAfter)
                report.put("singleSavedImages", singleAfter.size).put("savedImagesDecoded", true)
            }
            report.put("verified", true).put("phase","verified")
        } finally {
            if(activity != null)runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(folder,"screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                }
            }
            File(folder, "result.json").writeText(report.toString(2))
            println("LIVE_HOMEWORK_RESULT " + report)
            activity?.let { current ->
                main { current.finish() }
                await("Homework activity teardown did not complete") { current.isDestroyed }
            }
        }
    }
}
