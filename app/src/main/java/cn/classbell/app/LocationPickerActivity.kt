package cn.classbell.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*

/** Device fixes seed the selection. Only explicit confirmation returns a point to the caller. */
class LocationPickerActivity:StudyActivity() {
    private lateinit var latitude:EditText
    private lateinit var longitude:EditText
    private lateinit var address:EditText
    private lateinit var summary:TextView
    private lateinit var mapHost:LinearLayout
    private lateinit var coordinates:LinearLayout
    private lateinit var locateButton:Button
    private lateinit var confirmButton:Button
    private lateinit var device:DeviceLocation
    private var browser:WebView?=null
    private var selected:ManualLocation?=null
    private var request=0
    internal var locateSource:(((LocationFix?)->Unit)->Unit)?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);if(!valid()){finish();return}
        device=DeviceLocation(this)
        titleView.text=if(intent.getBooleanExtra("return",false))"选择位置" else "位置预览"
        body.addView(StudyUi.text(this,"先定位，再微调",22,true))
        body.addView(StudyUi.text(this,"① 手机定位　② 地图调整　③ 确认位置",12))
        val mapCard=StudyUi.card(this,body)
        val mapHeading=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        mapHeading.addView(StudyUi.text(this,"地图选点",16,true),LinearLayout.LayoutParams(0,-2,1f))
        mapHeading.addView(StudyUi.button(this,"重载"){showMap()}.apply{ModernUi.button(this,textOnly=true)},LinearLayout.LayoutParams(-2,-2))
        mapCard.addView(mapHeading)
        mapHost=StudyUi.column(this);mapCard.addView(mapHost)
        locateButton=StudyUi.button(this,"用手机定位选点",true){locate()};mapCard.addView(locateButton)
        val adjust=LinearLayout(this)
        adjust.addView(StudyUi.button(this,"调整经纬度"){coordinates.visibility=if(coordinates.visibility==View.VISIBLE)View.GONE else View.VISIBLE},LinearLayout.LayoutParams(0,-2,1f))
        adjust.addView(StudyUi.button(this,"回到校区"){cancelLocation();browser?.evaluateJavascript("window.centerCampus&&window.centerCampus();",null);state.text="已回到沙河校区视野，未更改已选位置。"},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ModernUi.dp(this@LocationPickerActivity,8)})
        mapCard.addView(adjust)
        coordinates=StudyUi.card(this,body).apply{visibility=View.GONE}
        coordinates.addView(StudyUi.text(this,"精确调整",16,true))
        coordinates.addView(StudyUi.text(this,"与手机 GPS 一致的经纬度（WGS-84）",12))
        fun input(label:String)=EditText(this).apply{hint=label;contentDescription=label;isSingleLine=true;isSaveEnabled=false;ModernUi.input(this);coordinates.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=ModernUi.dp(this@LocationPickerActivity,8)})}
        latitude=input("纬度（-90～90）");longitude=input("经度（-180～180）");address=input("地点名称（可选）")
        listOf(latitude,longitude).forEach{it.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}
        coordinates.addView(StudyUi.button(this,"使用输入坐标",true){diagnosticRunCatching{ManualLocation.parse(latitude.text.toString(),longitude.text.toString(),address.text.toString())}.onSuccess{choose(it);updateMap(it)}.onFailure{state.text=it.message}})
        body.addView(StudyUi.text(this,"初始视野：北京信息科技大学沙河校区。已有预备位置时优先显示该点。地图需要联网，无需申请 Key。",12))
        body.addView(StudyUi.text(this,"定位仅在你点击时获取。位置需确认后才会保存，这里不会提交签到或修改手机 GPS。",12))
        val footer=StudyUi.column(this).apply{setPadding(0,ModernUi.dp(this@LocationPickerActivity,8),0,ModernUi.dp(this@LocationPickerActivity,8));setBackgroundColor(ModernUi.background)}
        summary=StudyUi.text(this,"尚未选点 · 沙河校区仅为初始视野",12,true);footer.addView(summary)
        confirmButton=StudyUi.button(this,if(intent.getBooleanExtra("return",false))"确认使用此位置" else "预览所选位置",true){
            if(!valid())return@button
            val value=selected ?: return@button
            if(intent.getBooleanExtra("return",false)){setResult(RESULT_OK,value.putInto(Intent()));finish()} else state.text="已生成本地预览，未发送签到请求。"
        }.apply{isEnabled=false};footer.addView(confirmButton);pageRoot.addView(footer)
        val value=diagnosticRunCatching{
            if(savedInstanceState?.getBoolean("selected")==true)ManualLocation(savedInstanceState.getDouble("lat"),savedInstanceState.getDouble("lon"),savedInstanceState.getString("address").orEmpty())
            else if(intent.hasExtra("latitude"))ManualLocation.fromIntent(intent) else null
        }.getOrNull()
        value?.takeIf{diagnosticRunCatching{it.requireValid()}.isSuccess}?.let(::choose)
        showMap()
    }
    private fun cancelLocation(){request++;if(::device.isInitialized)device.cancel();if(::locateButton.isInitialized)locateButton.text="用手机定位选点"}
    private fun choose(value:ManualLocation){
        DiagnosticLog.event("LocationPicker", "point_selected coordinates_omitted=true")
        if(!valid())return;value.requireValid();cancelLocation();selected=value
        latitude.setText(value.latitude.toString());longitude.setText(value.longitude.toString());address.setText(value.address)
        summary.text="已选位置\n纬度 ${value.latitude}，经度 ${value.longitude}"+(if(value.address.isBlank())"" else " · ${value.address}")
        confirmButton.isEnabled=true;state.text="位置已选择，可继续调整。"
    }
    internal fun receiveMapPoint(lat:Double,lon:Double){if(valid())diagnosticRunCatching{ManualLocation(lat,lon).also{it.requireValid()}}.onSuccess(::choose)}
    private fun locate(){
        if(!valid())return
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATE);return}
        cancelLocation();val token=request
        locateButton.text="正在定位… 再点重新获取";state.text="正在获取手机位置，最多等待 30 秒…"
        val done:(LocationFix?)->Unit={fix->
            if(valid() && token==request){
                cancelLocation()
                if(fix==null || diagnosticRunCatching{fix.requireCurrent()}.isFailure)state.text="未取得新定位。请开启系统定位和精确位置权限，也可手动选点。"
                else {val point=ManualLocation(fix.latitude,fix.longitude);choose(point);updateMap(point);state.text="已按手机位置选点，可在地图上继续调整。"}
            }
        }
        locateSource?.invoke(done) ?: device.request(done)
    }
    private fun updateMap(point:ManualLocation){browser?.evaluateJavascript(LocationMapPage.selectionScript(point),null)}
    @SuppressLint("SetJavaScriptEnabled","ClickableViewAccessibility")
    private fun showMap(){
        closeMap();mapHost.removeAllViews()
        val css=assets.open("leaflet/leaflet.css").bufferedReader().use{it.readText()}
        val js=assets.open("leaflet/leaflet.js").bufferedReader().use{it.readText()}
        browser=WebView(this).apply{
            isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false
            settings.apply{javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;javaScriptCanOpenWindowsAutomatically=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;userAgentString+=" ClassBell/${BuildConfig.VERSION_NAME} (cn.classbell.app)"}
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage?)=true}
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    if(valid() && view===browser && request.isForMainFrame){val uri=request.url
                        LocationMapPage.picked(uri)?.let{receiveMapPoint(it.latitude,it.longitude)}
                        if(request.hasGesture() && uri.toString()=="https://www.openstreetmap.org/copyright")diagnosticRunCatching{startActivity(Intent(Intent.ACTION_VIEW,uri))}
                        if(uri.scheme=="cbmap" && uri.query==null)when(uri.host){
                            "ready"->state.text="点选地图或拖动标记调整位置。"
                            "error"->state.text="地图底图未能加载，请检查网络。仍可用手机定位或填写坐标。"
                        }
                    };return true
                }
                override fun onPageFinished(view:WebView,url:String?){DiagnosticLog.event("LocationMap", "page_finished");if(valid() && view===browser)selected?.let(::updateMap)}
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){DiagnosticLog.event("LocationMap", "load_error code=${error.errorCode} main=${request.isForMainFrame}", "WARN")}
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){DiagnosticLog.event("LocationMap", "http=${response.statusCode} main=${request.isForMainFrame}", "WARN")}
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=if(LocationMapPage.allowedResource(request.url))null else WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
            }
            setOnTouchListener{view,event->view.parent.requestDisallowInterceptTouchEvent(event.actionMasked !in listOf(android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL));false}
            mapHost.addView(this,LinearLayout.LayoutParams(-1,ModernUi.dp(this@LocationPickerActivity,300)))
            loadDataWithBaseURL("https://appassets.androidplatform.net/classbell-map/",LocationMapPage.html(css,js,selected),"text/html","UTF-8",null)
        }
    }
    private fun closeMap(){browser?.apply{stopLoading();(parent as? android.view.ViewGroup)?.removeView(this);destroy()};browser=null}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);DiagnosticLog.event("Permissions", "request=$code granted=${results.count{it==android.content.pm.PackageManager.PERMISSION_GRANTED}} total=${results.size}");if(code==LOCATE && valid()){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate() else state.text="未允许精确定位，你仍可在地图或经纬度输入中手动选点。"}}
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);selected?.let{out.putBoolean("selected",true);out.putDouble("lat",it.latitude);out.putDouble("lon",it.longitude);out.putString("address",it.address)}}
    override fun onPause(){browser?.onPause();super.onPause()}
    override fun onResume(){super.onResume();browser?.onResume()}
    override fun onStop(){cancelLocation();super.onStop()}
    override fun onDestroy(){cancelLocation();closeMap();super.onDestroy()}
    companion object{const val LOCATE=5301}
}
