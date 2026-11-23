package cn.classbell.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
class LocalScanWebTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp

    private fun loginFixture() {
        app.prefs.edit().clear().commit()
        app.courseMemory.activate(app.prefs, accountKey("web-scan-fixture"), emptyList())
    }

    private fun View.webView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).webView() }
        else -> null
    }

    @Test fun webLaunchesOnlyOneScannerAndReturnsStatusAfterResume() {
        loginFixture()
        val controller = Robolectric.buildActivity(LocalScanWebActivity::class.java).setup()
        val activity = controller.get()
        try {
            val web = requireNotNull(activity.window.decorView.webView())
            val view = shadowOf(web)
            assertNotNull(view.getJavascriptInterface("ClassBellScan"))
            view.webViewClient.onPageFinished(web, LocalScanWebActivity.PAGE)
            activity.launchScanner(QrScanActivity.PreviewInput.CAMERA)
            activity.launchScanner(QrScanActivity.PreviewInput.GALLERY)
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertEquals(QrScanActivity::class.java.name, launch.intent.component?.className)
            assertEquals(LocalScanWebActivity.SCAN, launch.requestCode)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            controller.pause()
            shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent().putExtra(QrScanActivity.MATERIAL, "not-for-the-page"))
            assertNull(view.lastEvaluatedJavascript)
            controller.resume()
            assertEquals("window.classbellScanResult('recognized')", view.lastEvaluatedJavascript)
            activity.launchScanner(QrScanActivity.PreviewInput.GALLERY)
            val next = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(next)
            val picker = Robolectric.buildActivity(QrScanActivity::class.java, next.intent).setup()
            try {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(picker.get()).nextStartedActivityForResult.intent.action)
                picker.pause().resume()
                assertNull(shadowOf(picker.get()).nextStartedActivityForResult)
            } finally { picker.pause().stop().destroy() }
            shadowOf(activity).receiveResult(next.intent, Activity.RESULT_CANCELED, null)
            assertEquals("window.classbellScanResult('cancelled')", view.lastEvaluatedJavascript)
        } finally {
            controller.pause().stop().destroy()
            app.prefs.edit().clear().commit()
        }
    }

    @Test fun servesOnlyBundledAssetsWithOfflineSettings() {
        loginFixture()
        val controller = Robolectric.buildActivity(LocalScanWebActivity::class.java).setup()
        val activity = controller.get()
        try {
            val web = requireNotNull(activity.window.decorView.webView())
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            val page = activity.assetResponse(request(LocalScanWebActivity.PAGE, true))
            assertEquals(200, page.statusCode)
            assertTrue(page.data.bufferedReader().use { it.readText() }.contains("classbell-scan.js"))
            assertTrue(page.responseHeaders.getValue("Content-Security-Policy").contains("frame-ancestors 'none'"))
            val origin = LocalScanWebActivity.PAGE.substringBeforeLast('/')
            val wasm = activity.assetResponse(request("$origin/classbell-scan.wasm", false))
            assertEquals(200, wasm.statusCode)
            assertEquals("application/wasm", wasm.mimeType)
            assertArrayEquals(byteArrayOf(0, 97, 115, 109), wasm.data.use { it.readNBytes(4) })
            assertEquals(403, activity.assetResponse(request("$origin/classbell-scan.js", true)).statusCode)
            assertEquals(403, activity.assetResponse(request(LocalScanWebActivity.PAGE + "?other=1", true)).statusCode)
            assertEquals(403, activity.assetResponse(request(LocalScanWebActivity.PAGE, true, "POST")).statusCode)
            val untrusted = request("https://example.invalid/frame", false)
            assertEquals(403, activity.assetResponse(untrusted).statusCode)
            assertTrue(shadowOf(web).webViewClient.shouldOverrideUrlLoading(web, untrusted))
        } finally {
            controller.pause().stop().destroy()
            app.prefs.edit().clear().commit()
        }
    }

    @Test fun previewScannerReturnsNoIntentContentForAValidCode() {
        loginFixture()
        val host = Robolectric.buildActivity(LocalScanWebActivity::class.java).setup()
        val scanner = Robolectric.buildActivity(QrScanActivity::class.java, QrScanActivity.previewIntent(host.get())).setup()
        try {
            val activity = scanner.get()
            activity.accept("https://example.invalid/not-a-code")
            assertFalse(activity.isFinishing)
            activity.accept("https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=0123456789abcdef0123456789abcdef")
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertNull(shadowOf(activity).resultIntent)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            scanner.pause().stop().destroy()
            host.pause().stop().destroy()
            app.prefs.edit().clear().commit()
        }
    }

    private fun request(url: String, main: Boolean, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
