package cn.classbell.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Official visible login; loading/errors are reported locally and screenshots are allowed. */
class WebLoginActivity : Activity() {
    private val app get() = application as BellApp
    private lateinit var browser: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var complete: Button
    private lateinit var cancel: Button
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var busy = false
    private var generation = 0
    private var loading = false
    private var pageError = false
    private var rendererGone = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(value: Int) = ModernUi.dp(this, value)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(ModernUi.background)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(16), insets.systemWindowInsetTop + dp(16), dp(16), insets.systemWindowInsetBottom + dp(16))
            insets
        }
        root.addView(StudyUi.header(this,"官方网页登录"){finish()}.first)
        status = TextView(this).apply {
            text = "正在打开学习通官方登录页…"; textSize = 13f; setTextIsSelectable(true)
            setTextColor(ModernUi.muted); setPadding(0, 0, 0, dp(10))
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(-1, 6))
        setContentView(root)
        try {
            browser = WebView(this)
            browser.isVerticalScrollBarEnabled=false;browser.isHorizontalScrollBarEnabled=false
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100 && !busy) View.GONE else View.VISIBLE
                }
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https") return false
                    if (request.isForMainFrame) {
                        pageError = true
                        status.text = getString(R.string.web_blocked_navigation)
                    }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    DiagnosticLog.event("LoginWeb", "page_started ${DiagnosticLog.endpoint(url.orEmpty())}")
                    loading = true; pageError = false
                    progress.visibility = View.VISIBLE
                    status.text = getString(R.string.web_loading_domain, Uri.parse(url ?: "").host.orEmpty())
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    DiagnosticLog.event("LoginWeb", "page_finished error=$pageError ${DiagnosticLog.endpoint(url.orEmpty())}")
                    loading = false
                    if (!busy) progress.visibility = View.GONE
                    if (!pageError && !busy) status.text = getString(R.string.web_domain, Uri.parse(url ?: "").host.orEmpty())
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    DiagnosticLog.event("LoginWeb", "resource_failed code=${error.errorCode} main=${request.isForMainFrame} endpoint=${DiagnosticLog.endpoint(request.url.toString())}", "WARN")
                    if (request.isForMainFrame) pageFailed("官方页面加载失败（错误 ${error.errorCode}）。请检查网络，然后点“重新加载登录页”。")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    DiagnosticLog.event("LoginWeb", "http_error status=${response.statusCode} main=${request.isForMainFrame} endpoint=${DiagnosticLog.endpoint(request.url.toString())}", "WARN")
                    if (request.isForMainFrame) pageFailed("学习通返回 HTTP ${response.statusCode}，请稍后重新加载。")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    DiagnosticLog.event("LoginWeb", "tls_rejected code=${error.primaryError} endpoint=${DiagnosticLog.endpoint(error.url.orEmpty())}", "WARN")
                    handler.cancel()
                    pageFailed("官方页面的安全连接失败，请检查手机日期、网络和系统网页组件后重试。")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true
                    pageFailed("系统网页组件已退出，请点“重新加载登录页”重试。")
                    view.destroy()
                    return true
                }
            }
            root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(browser, false)
            // Load immediately. A delayed cookie-clearing callback must not gate the whole page.
            loadLoginPage()
        } catch (diagnosticError: Exception) { DiagnosticLog.error("WebLoginActivity", diagnosticError);
            pageFailed("系统网页组件未能启动。请更新系统 WebView 或浏览器后重新打开此页面。")
            root.addView(TextView(this).apply { text = "网页组件不可用，未载入登录页。" }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        complete = Button(this).apply {
            text = "我已完成网页登录 · 验证并读取课程"; ModernUi.button(this, primaryAction = true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }; setLoggedClickListener { finishLogin() }
        }
        root.addView(complete)
        cancel = Button(this).apply { text = "取消验证"; ModernUi.button(this); visibility = View.GONE; setLoggedClickListener { cancelVerification(); status.text = "已取消验证，可以继续登录或重试。" } }
        root.addView(cancel)
        root.addView(Button(this).apply {
            text = "重新加载登录页"
            ModernUi.button(this, textOnly = true)
            setLoggedClickListener {
                cancelVerification()
                if (rendererGone || !::browser.isInitialized) recreate() else loadLoginPage()
            }
        })
    }

    private fun loadLoginPage() {
        loading = true; pageError = false
        status.text = "正在打开学习通官方登录页…"
        progress.visibility = View.VISIBLE
        browser.loadUrl(ChaoxingProtocol.LOGIN_PAGE)
        main.postDelayed({
            if (loading && !pageError && !isDestroyed && !busy) {
                status.text = "登录页仍在加载。可等待或点“重新加载登录页”；这一步不需要通知权限。"
            }
        }, 15_000)
    }
    private fun pageFailed(message: String) {
        DiagnosticLog.event("LoginWeb", message, "WARN")
        loading = false; pageError = true
        status.text = message
        progress.visibility = View.GONE
    }
    private fun finishLogin() {
        if (busy) { showInfo("正在验证", "正在读取学习通课程，请稍候，或点“取消验证”。"); return }
        if (!::browser.isInitialized || rendererGone) { showInfo("登录页未打开", "请先重新加载官方登录页，完成登录后再验证。"); return }
        val manager = CookieManager.getInstance()
        manager.flush()
        val headers = ChaoxingProtocol.hosts.associate { host -> "https://$host/" to manager.getCookie("https://$host/").orEmpty() }
        if (headers.values.all { it.isBlank() }) {
            status.text = "还没有读到登录状态。请先在上方官方页面登录，再点击验证。"
            showInfo("请先完成网页登录", status.text.toString())
            return
        }
        busy = true
        val token = ++generation
        complete.text = "正在验证并读取课程…"
        cancel.visibility = View.VISIBLE; progress.visibility = View.VISIBLE
        status.text = "正在验证学习通登录状态并读取课程…"
        app.prefs.edit().putBoolean("loggedIn", false).apply()
        task = worker.submit {
            try {
                app.cookies.clear()
                headers.forEach { (url, cookie) -> if (cookie.isNotEmpty()) app.cookies.importWebCookie(url, cookie) }
                val courses = app.api.courses()
                if (app.cookies.userId().isNullOrBlank()) throw ApiProblem("未读取到完整登录状态，请先在官方页面完成登录。")
                if (token != generation || Thread.currentThread().isInterrupted) return@submit
                app.saveSession(courses, "官方网页登录", "", false, updateCredentials=false)
                val message = "网页登录验证成功，读取到 ${courses.size} 个课程/班级。课程选择和标签已按账号载入，请核对后重新开始监测。"
                app.prefs.edit().putString("lastAuthMessage", message).apply()
                runOnUiThread {
                    if (!isDestroyed && token == generation) {
                        busy = false
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                }
            } catch (e: Exception) { DiagnosticLog.error("WebLoginActivity", e);
                runOnUiThread {
                    if (!isDestroyed && token == generation) {
                        busy = false
                        val message = if (e is LoginRequired) "尚未完成学习通登录，请先在上方页面完成登录，再点验证。" else FailureMessages.describe(e)
                        status.text = message
                        complete.text = "我已完成网页登录 · 验证并读取课程"
                        cancel.visibility = View.GONE; progress.visibility = View.GONE
                        showInfo("验证未完成", message)
                    }
                }
            }
        }
    }
    private fun cancelVerification() {
        generation++
        if (busy) { app.api.cancelRequests(); task?.cancel(true) }
        busy = false
        if (::complete.isInitialized) complete.text = "我已完成网页登录 · 验证并读取课程"
        if (::cancel.isInitialized) cancel.visibility = View.GONE
        progress.visibility = View.GONE
    }
    private fun showInfo(title: String, message: String) {
        if (!isFinishing && !isDestroyed) ModernUi.dialog(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
    }
    override fun onDestroy() {
        cancelVerification(); main.removeCallbacksAndMessages(null); worker.shutdownNow()
        if (::browser.isInitialized && !rendererGone) { browser.stopLoading(); browser.destroy() }
        super.onDestroy()
    }
}
