package cn.classbell.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*

/** Official course/homework form with an explicit system document picker, never scripted submission. */
class OfficialPageActivity:Activity() {
    private val app get()=application as BellApp
    private lateinit var browser:WebView
    private lateinit var state:TextView
    private lateinit var loading:ProgressBar
    private var qrHelp:LinearLayout?=null
    private var qrProblem:TextView?=null
    private var fileResult:ValueCallback<Array<Uri>>?=null
    private var geoResult:Pair<String,GeolocationPermissions.Callback>?=null
    private var materialDownloads:CourseMaterialDownloads?=null
    private var owner=""
    private var revision=0
    @Volatile private var dead=false
    private var alive=true
    private var issue:String?=null
    private var jsDialog:android.app.AlertDialog?=null
    private val notifications=linkedSetOf<String>()
    private fun problem(message:String) {
        DiagnosticLog.event("OfficialWeb", message, "WARN")
        issue=message
        state.text=if(qrHelp==null)message else "学习通网页未能继续"
        qrHelp?.visibility=View.VISIBLE
        qrProblem?.text=message
        if(qrHelp!=null && ::browser.isInitialized && alive)browser.visibility=View.GONE
    }
    private val changed:()->Unit={if(!current()){fileResult?.onReceiveValue(null);fileResult=null;if(::browser.isInitialized && alive)browser.stopLoading();finish()}}
    private fun current()=!dead && owner.isNotBlank() && app.prefs.getBoolean("loggedIn",false) && owner==app.prefs.getString("identity","") && revision==app.signSessionGeneration
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        owner=app.prefs.getString("identity","").orEmpty();revision=app.signSessionGeneration
        val url=intent.getStringExtra("url").orEmpty()
        if(!current() || !OfficialPagePolicy.initial(url)){finish();return}
        val isQr=SignQr.parse(url)!=null
        val root=StudyUi.column(this).apply{setBackgroundColor(ModernUi.background)}
        root.setOnApplyWindowInsetsListener{v,i->@Suppress("DEPRECATION") v.setPadding(ModernUi.dp(this,12),i.systemWindowInsetTop,ModernUi.dp(this,12),i.systemWindowInsetBottom);i}
        val header=StudyUi.header(this,intent.getStringExtra("title").orEmpty().take(80)){onBackPressed()}
        header.first.addView(ModernUi.navigation(this,"refresh","重新加载"){if(alive && ::browser.isInitialized)browser.reload() else recreate()},LinearLayout.LayoutParams(ModernUi.dp(this,48),ModernUi.dp(this,48)))
        root.addView(header.first)
        state=StudyUi.text(this,"正在打开学习通内容…",12);root.addView(state)
        val actions=LinearLayout(this)
        actions.addView(StudyUi.button(this,"打开学习通"){
            val target=if(!isQr && ::browser.isInitialized && alive)browser.url?.takeIf{OfficialPagePolicy.allowed(it)} ?: url else url
            try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(target)).setPackage(NotificationRules.SOURCE_PACKAGE))}
            catch (diagnosticError: Exception) { DiagnosticLog.error("OfficialPageActivity", diagnosticError);val home=packageManager.getLaunchIntentForPackage(NotificationRules.SOURCE_PACKAGE)
                if(home!=null)diagnosticRunCatching{startActivity(home);Toast.makeText(this,if(isQr)"已打开学习通首页，请使用学习通扫一扫。" else "学习通未接收此内容链接，已打开首页。",Toast.LENGTH_LONG).show()}.onFailure{problem("未能启动学习通，请从手机桌面打开。")}
                else problem("未能启动学习通，请从手机桌面打开。")}
        },LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=ModernUi.dp(this@OfficialPageActivity,8)})
        actions.addView(StudyUi.button(this,"浏览器打开"){
            val target=if(!isQr && ::browser.isInitialized && alive)browser.url ?: url else url
            OfficialPagePolicy.navigation(target)?.let{diagnosticRunCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it)));Toast.makeText(this,"浏览器需单独登录学习通。",Toast.LENGTH_SHORT).show()}.onFailure{problem("系统浏览器未能打开。")}}
        },LinearLayout.LayoutParams(0,-2,1f));root.addView(actions)
        if(intent.getBooleanExtra("courseMaterials",false)) {
            val downloads=CourseMaterialDownloads(this,app.cookies.snapshot(),::current){state.text=it}
            materialDownloads=downloads
            root.addView(StudyUi.button(this,"批量下载资料"){downloads.show()})
        }
        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(loading)
        if(isQr)qrHelp=StudyUi.card(this,root).apply {
            visibility=View.GONE
            addView(StudyUi.text(this@OfficialPageActivity,"二维码已识别",20,true))
            qrProblem=StudyUi.text(this@OfficialPageActivity,"",14).also { addView(it) }
            addView(StudyUi.text(this@OfficialPageActivity,"二维码是否有效由学习通判断。如果已经过期或换码，请重新扫描老师当前展示的二维码。",14))
            addView(StudyUi.button(this@OfficialPageActivity,"返回扫码页"){finish()})
        }
        setContentView(root)
        try {
            browser=WebView(this)
            val userAgent=browser.settings.userAgentString
            browser.isVerticalScrollBarEnabled=false;browser.isHorizontalScrollBarEnabled=false
            browser.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=true;builtInZoomControls=true;displayZoomControls=false;useWideViewPort=true;loadWithOverviewMode=true}
            browser.webViewClient=object:WebViewClient(){
                @Volatile private var currentPage:String?=null
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    return navigate(request.url.toString(),request.isForMainFrame,request.method)
                }
                @Deprecated("Legacy WebView navigation")
                override fun shouldOverrideUrlLoading(view:WebView,value:String)=navigate(value,true,"GET")
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    if(!current())return WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
                    materialDownloads?.observe(request.url.toString(),request.method,request.requestHeaders,currentPage,userAgent)
                    return null
                }
                override fun onPageStarted(view:WebView,value:String?,icon:android.graphics.Bitmap?){
                    DiagnosticLog.event("OfficialWeb", "page_started ${DiagnosticLog.endpoint(value.orEmpty())}")
                    currentPage=value
                    issue=null;notifications.clear();state.text="正在载入学习通网页…"
                    qrHelp?.visibility=View.GONE;view.visibility=View.VISIBLE
                }
                override fun onPageFinished(view:WebView,url:String){DiagnosticLog.event("OfficialWeb", "page_finished has_issue=${issue!=null} ${DiagnosticLog.endpoint(url)}");if(current()) {readyBridge();if(issue==null)state.text="学习通网页 · 操作结果以页面提示为准"}}
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){DiagnosticLog.event("OfficialWeb", "resource_failed code=${error.errorCode} main=${request.isForMainFrame} ${DiagnosticLog.endpoint(request.url.toString())}", "WARN");if(request.isForMainFrame)problem("页面加载未完成，请检查网络后重试。")}
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){DiagnosticLog.event("OfficialWeb", "http_error status=${response.statusCode} main=${request.isForMainFrame} endpoint=${DiagnosticLog.endpoint(request.url.toString())}", "WARN");if(request.isForMainFrame)problem("学习通页面返回 HTTP ${response.statusCode}，请稍后重试。")}
                override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){DiagnosticLog.event("OfficialWeb", "tls_rejected code=${error.primaryError} endpoint=${DiagnosticLog.endpoint(error.url.orEmpty())}", "WARN");handler.cancel();problem("安全连接未通过，请检查网络与手机日期。")}
                override fun onFormResubmission(view:WebView,dontResend:android.os.Message,resend:android.os.Message){dontResend.sendToTarget();problem("为避免重复提交，没有重新发送表单，请先核对作业状态。")}
                override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {DiagnosticLog.event("OfficialWeb", "renderer_gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}", "ERROR");alive=false;(view.parent as? android.view.ViewGroup)?.removeView(view);view.destroy();problem("网页组件已退出，点右上角重新加载。");return true}
            }
            browser.webChromeClient=object:WebChromeClient(){
                override fun onProgressChanged(view:WebView,value:Int){loading.progress=value;loading.visibility=if(value>=100)View.GONE else View.VISIBLE}
                override fun onJsAlert(view:WebView,url:String,message:String,result:JsResult):Boolean=scriptDialog(url,message,result,false)
                override fun onJsConfirm(view:WebView,url:String,message:String,result:JsResult):Boolean=scriptDialog(url,message,result,true)
                override fun onConsoleMessage(message:ConsoleMessage):Boolean {
                    if(message.messageLevel() in setOf(ConsoleMessage.MessageLevel.ERROR,ConsoleMessage.MessageLevel.WARNING))DiagnosticLog.event("OfficialWebJS", "level=${message.messageLevel()} line=${message.lineNumber()} source=${DiagnosticLog.endpoint(message.sourceId().orEmpty())} kind=${when{message.message().contains("TypeError")->"TypeError";message.message().contains("ReferenceError")->"ReferenceError";else->"other"}}", "WARN")
                    if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR && (message.message().contains("TypeError") || message.message().contains("ReferenceError")))problem("网页部分功能运行失败，可尝试重新加载，或在学习通中处理。")
                    return true
                }
                override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {
                    fileResult?.onReceiveValue(null);fileResult=null
                    if(!current() || !OfficialPagePolicy.allowed(view.url.orEmpty())){callback.onReceiveValue(null);return true}
                    fileResult=callback
                    val types=params.acceptTypes.filter{it.contains('/') && it.length<100}.toTypedArray()
                    DiagnosticLog.event("WebFile", "picker_open multiple=${params.mode==FileChooserParams.MODE_OPEN_MULTIPLE} accepted_types=${types.joinToString(",")}")
                    val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if(types.size==1)types[0] else "*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.mode==FileChooserParams.MODE_OPEN_MULTIPLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if(types.size>1)pick.putExtra(Intent.EXTRA_MIME_TYPES,types)
                    try {@Suppress("DEPRECATION") startActivityForResult(pick,FILE_REQUEST)}catch (diagnosticError: Exception) { DiagnosticLog.error("OfficialPageActivity", diagnosticError);callback.onReceiveValue(null);fileResult=null;state.text="未能打开系统文件选择器。"}
                    return true
                }
                override fun onGeolocationPermissionsShowPrompt(origin:String,callback:GeolocationPermissions.Callback){
                    if(!current() || !OfficialPagePolicy.allowed(origin)){callback.invoke(origin,false,false);return}
                    geoResult=origin to callback
                    ModernUi.dialog(this@OfficialPageActivity).setTitle("读取当前位置").setMessage("学习通页面请求本次使用手机的真实定位。")
                        .setPositiveButton("允许"){_,_->
                            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)completeGeo(true)
                            else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION_REQUEST)
                        }.setNegativeButton("拒绝"){_,_->completeGeo(false)}.setOnCancelListener{completeGeo(false)}.show()
                }
            }
            root.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
            val cookies=app.cookies.snapshot();val manager=CookieManager.getInstance();manager.setAcceptCookie(true);manager.setAcceptThirdPartyCookies(browser,false)
            manager.removeAllCookies {
                if(current()) {
                    if(cookies.isEmpty())problem("没有可用的登录会话，请返回账号中心重新登录。")
                    else {var remaining=cookies.size;cookies.forEach{cookie->manager.setCookie("https://${cookie.domain.trimStart('.')}/",cookie.toString()){
                        remaining--;if(remaining==0 && current()){manager.flush();browser.loadUrl(url)}
                    }}}
                }
            }
            app.addUiListener(changed)
        }catch (diagnosticError: Exception) { DiagnosticLog.error("OfficialPageActivity", diagnosticError);problem("系统网页组件不可用，请更新 Android System WebView 后重试。")}
    }
    private fun readyBridge() {
        if(current() && alive && OfficialPagePolicy.allowed(browser.url.orEmpty()))browser.evaluateJavascript("if(window.jsBridge && typeof window.jsBridge.setDevice === 'function' && !window.jsBridge.isReady){window.jsBridge.setDevice('ios');}",null)
    }
    private fun navigate(value:String,mainFrame:Boolean,method:String):Boolean {
        if(!current())return true
        if(value.startsWith("jsbridge://",true)) {
            if(value.equals("jsbridge://NotificationReady",true)){readyBridge();return true}
            val id=OfficialPagePolicy.bridgeId(value)
            if(id!=null && OfficialPagePolicy.allowed(browser.url.orEmpty())) {
                val source=browser.url.orEmpty();val key="$source#$id"
                if(notifications.size<128 && notifications.add(key))browser.evaluateJavascript("window.jsBridge && window.jsBridge.popNotificationObject($id)"){encoded->
                    if(current() && alive && browser.url==source)OfficialPagePolicy.notification(encoded)?.let{handleNotification(it)}
                }
            }
            return true
        }
        val target=OfficialPagePolicy.navigation(value)
        if(mainFrame)DiagnosticLog.event("OfficialWeb", "navigation allowed=${target!=null} method=$method endpoint=${DiagnosticLog.endpoint(value)}")
        if(target==null){if(mainFrame)problem("该页面需要学习通专有功能，请用上方“打开学习通”处理。");return true}
        if(Uri.parse(target).host=="passport2.chaoxing.com"){problem("登录会话已过期，请返回账号中心重新登录。");return true}
        if(target!=value) {
            if(method=="GET")browser.loadUrl(target) else problem("此页面要求重新发送表单，请回到作业列表核对状态。")
            return true
        }
        return false
    }
    internal fun handleNotification(item:org.json.JSONObject) {
        if(!current() || !alive || !OfficialPagePolicy.allowed(browser.url.orEmpty()))return
        val name=item.optString("name");val info=item.optJSONObject("userInfo") ?: org.json.JSONObject()
        when(name) {
            "CLIENT_OPEN_URL" -> {val target=OfficialPagePolicy.navigation(info.optString("webUrl"));if(target==null)problem("此内容需要在学习通中打开。") else if(!navigate(target,true,"GET"))browser.loadUrl(target)}
            "CLIENT_EXIT_WEBAPP","CLIENT_EXIT_LEVEL","CLIENT_BACK_HOME" -> onBackPressed()
            "CLIENT_DISPLAY_MESSAGE" -> {val message=info.optString("message").ifBlank{info.optString("msg")}.take(500);if(message.isNotBlank())Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
            "CLIENT_DISMISS_MESSAGE","CLIENT_REFRESH_STATUS","CLIENT_REFRESH_EVENT","CLIENT_CHANGE_WEBVIEW_TITLE","CLIENT_SET_WEBVIEW_TITLE","CLIENT_CUSTOM_MENU" -> Unit
            else -> problem("该功能需要学习通客户端支持，请用上方“打开学习通”处理。")
        }
    }
    private fun scriptDialog(url:String,message:String,result:JsResult,confirm:Boolean):Boolean {
        if(!current() || !OfficialPagePolicy.allowed(url)){result.cancel();return true}
        jsDialog?.dismiss();var decided=false
        val builder=ModernUi.dialog(this).setTitle(if(confirm)"确认操作" else "学习通提示").setMessage(message.take(3000))
            .setPositiveButton(if(confirm)"确认" else "知道了"){_,_->decided=true;result.confirm()}
        if(confirm)builder.setNegativeButton("取消"){_,_->decided=true;result.cancel()}
        jsDialog=builder.create().apply{setOnDismissListener{if(!decided)result.cancel()};show()};return true
    }
    @Deprecated("Web history before closing the screen")
    override fun onBackPressed(){if(::browser.isInitialized && alive && browser.canGoBack())browser.goBack() else finish()}
    private fun completeGeo(allowed:Boolean){if(geoResult!=null)DiagnosticLog.event("WebLocation", "allowed=${allowed && current()} session_valid=${current()}");geoResult?.let{it.second.invoke(it.first,allowed && current(),false)};geoResult=null}
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==CourseMaterialDownloads.REQUEST)materialDownloads?.save(data?.data.takeIf{resultCode==RESULT_OK})
        if(requestCode==FILE_REQUEST) {
            val selected=mutableListOf<Uri>();data?.data?.let{selected+=it};data?.clipData?.let{clip->for(i in 0 until minOf(clip.itemCount,10))selected+=clip.getItemAt(i).uri}
            val files=if(resultCode==RESULT_OK && current())OfficialPagePolicy.files(selected) else emptyList()
            DiagnosticLog.event("WebFile", "picker_result ok=${resultCode==RESULT_OK} selected=${selected.size} accepted=${files.size} session_valid=${current()}")
            fileResult?.onReceiveValue(files.takeIf{it.isNotEmpty()}?.toTypedArray());fileResult=null
            if(files.isNotEmpty())state.text="已选择 ${files.size} 个文件，请在作业页面核对后提交。"
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);DiagnosticLog.event("Permissions", "request=$code granted=${results.count{it==android.content.pm.PackageManager.PERMISSION_GRANTED}} total=${results.size}");if(code==LOCATION_REQUEST)completeGeo(results.isNotEmpty() && results.any{it==PackageManager.PERMISSION_GRANTED})}
    override fun onDestroy(){dead=true;materialDownloads?.close();jsDialog?.dismiss();jsDialog=null;fileResult?.onReceiveValue(null);fileResult=null;completeGeo(false);app.removeUiListener(changed);if(::browser.isInitialized && alive){browser.stopLoading();browser.destroy()};super.onDestroy()}
    companion object {
        const val FILE_REQUEST=5101;const val LOCATION_REQUEST=5102
        fun open(activity:Activity,url:String,title:String,courseMaterials:Boolean=false){DiagnosticLog.event("OfficialWeb", "open title=${DiagnosticLog.label(title)} materials=$courseMaterials allowed=${OfficialPagePolicy.initial(url)} endpoint=${DiagnosticLog.endpoint(url)}");if(OfficialPagePolicy.initial(url))activity.startActivity(Intent(activity,OfficialPageActivity::class.java).putExtra("url",url).putExtra("title",title).putExtra("courseMaterials",courseMaterials))}
    }
}
