package cn.classbell.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.ByteArrayInputStream

/** The bridge is attached only to bundled ClassBell content, never to a remote page. */
class LocalScanWebActivity: StudyActivity() {
    private lateinit var page: WebView
    private var pending = false
    private var foreground = false
    private var disposed = false
    private var pageReady = false
    private var resultToShow: ScanStatus? = null

    private enum class ScanStatus(val wireValue: String) {
        SCANNING("scanning"), RECOGNIZED("recognized"), CANCELLED("cancelled"), UNAVAILABLE("unavailable")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!valid()) { finish(); return }
        titleView.text = "网页扫码工具"
        pending = savedInstanceState?.getBoolean(SCAN_PENDING) == true
        page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Deprecated("Legacy navigation")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = assetResponse(request)
                override fun onPageFinished(view: WebView, url: String) {
                    if (!disposed && url == PAGE && view.url == PAGE) {
                        pageReady = true
                        if (pending) resultToShow = ScanStatus.SCANNING
                        flushResult()
                    }
                }
            }
            addJavascriptInterface(ScanBridge(), "ClassBellScan")
        }
        body.addView(page, LinearLayout.LayoutParams(-1, ModernUi.dp(this, 380)))
        page.loadUrl(PAGE)
    }

    private inner class ScanBridge {
        @JavascriptInterface
        fun openCamera() { main.post { launchScanner(QrScanActivity.PreviewInput.CAMERA) } }

        @JavascriptInterface
        fun openGallery() { main.post { launchScanner(QrScanActivity.PreviewInput.GALLERY) } }
    }

    @Suppress("DEPRECATION")
    internal fun launchScanner(input: QrScanActivity.PreviewInput) {
        if (disposed || isFinishing || !valid() || pending || !::page.isInitialized || page.url != PAGE) return
        if (!foreground) { showResult(ScanStatus.UNAVAILABLE); return }
        pending = true
        try { startActivityForResult(QrScanActivity.previewIntent(this, input), SCAN) }
        catch (_: Exception) { pending = false; showResult(ScanStatus.UNAVAILABLE) }
    }

    @Deprecated("Platform scanner result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN || !pending) return
        pending = false
        showResult(if (resultCode == RESULT_OK) ScanStatus.RECOGNIZED else ScanStatus.CANCELLED)
    }

    private fun showResult(status: ScanStatus) {
        resultToShow = status
        flushResult()
    }

    private fun flushResult() {
        val status = resultToShow ?: return
        if (!disposed && foreground && pageReady && !isFinishing && valid() && page.url == PAGE) {
            resultToShow = null
            page.evaluateJavascript("window.classbellScanResult('${status.wireValue}')", null)
        }
    }

    internal fun assetResponse(request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        val asset = if (request.method != "GET") null else if (request.isForMainFrame) {
            if (url == PAGE) "classbell-scan.html" to "text/html" else null
        } else when (url) {
            BASE + "classbell-scan.js" -> "classbell-scan.js" to "text/javascript"
            BASE + "wasm_exec.js" -> "wasm_exec.js" to "text/javascript"
            BASE + "classbell-scan.wasm" -> "classbell-scan.wasm" to "application/wasm"
            else -> null
        }
        val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        if (asset == null) return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", headers, ByteArrayInputStream(byteArrayOf()))
        return try {
            WebResourceResponse(asset.second, if (asset.second == "application/wasm") null else "UTF-8", 200, "OK", headers, assets.open(asset.first))
        } catch (_: java.io.IOException) {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, ByteArrayInputStream(byteArrayOf()))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SCAN_PENDING, pending)
    }

    override fun onResume() { super.onResume(); foreground = true; flushResult() }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onDestroy() {
        disposed = true
        if (::page.isInitialized) {
            page.removeJavascriptInterface("ClassBellScan")
            body.removeView(page)
            page.stopLoading()
            page.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val BASE = "https://appassets.androidplatform.net/"
        internal const val PAGE = BASE + "classbell-scan.html"
        internal const val SCAN = 5470
        private const val SCAN_PENDING = "localScanPending"
    }
}
