package cn.classbell.app

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.os.PowerManager
import android.content.Context

/** Owned-account receive-only channel. It never sends chat, read receipts, or attendance submissions. */
internal interface ActivityReceiver { fun start(); fun stop() }

internal class BackendImClient(private val app:BellApp,private val received:(SignActivity)->Unit) : ActivityReceiver {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val owner=app.prefs.getString("identity","").orEmpty()
    private val revision=app.signSessionGeneration
    private val nonce=UUID.randomUUID().toString()
    private val began=System.currentTimeMillis()
    private val cookies=app.cookies.snapshot()
    private val web=OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("BackendImClient")).cookieJar(object:CookieJar {
        override fun loadForRequest(url:HttpUrl)=if(ChaoxingProtocol.allowed(url))cookies.filter{it.matches(url) && it.expiresAt>System.currentTimeMillis()} else emptyList()
        override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {}
    }).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(30,TimeUnit.SECONDS).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val im=OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("BackendImClient")).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    @Volatile private var stopped=false
    @Volatile private var socket:WebSocket?=null
    private var heartbeat:ScheduledFuture<*>?=null
    private var authDeadline:ScheduledFuture<*>?=null
    private var retry:ScheduledFuture<*>?=null
    private var failures=0
    private var authenticated=false
    private var loginFrame:ByteArray?=null
    private var run=0
    private val fetched=linkedMapOf<String,Long>()
    private val verified=linkedSetOf<String>()
    private var fetchCount=0
    private var fetchWindow=0L
    private var verifyWindow=0L
    private var verifyCount=0
    private val eventLock=(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"${app.packageName}:im-event-verification").apply {setReferenceCounted(false)}

    override fun start() {
        app.prefs.edit().putString("backend.run",nonce).putString("backend.owner",owner).putBoolean("backend.connected",false).apply()
        status("正在连接学习通消息服务…")
        post { connect() }
    }
    private fun guard() {
        if(stopped || Thread.currentThread().isInterrupted || owner.isBlank() || owner!=app.prefs.getString("identity","") ||
            revision!=app.signSessionGeneration || !app.prefs.getBoolean("loggedIn",false) || !app.prefs.getBoolean("monitorEnabled",false) ||
            !app.prefs.getBoolean("backendPush",false)) throw InterruptedException()
    }
    private fun status(text:String,connected:Boolean=false) {
        DiagnosticLog.event("BackendIM", "status=${text.substringBefore(" · ")} connected=$connected")
        if(app.prefs.getString("backend.run","")!=nonce)return
        app.prefs.edit().putString("backend.status",text).putBoolean("backend.connected",connected).apply();app.notifyUi()
    }
    private fun post(action:()->Unit) {
        if(stopped)return
        diagnosticRunCatching { worker.execute {
            try {guard();action()}
            catch (diagnosticError: InterruptedException) { DiagnosticLog.error("BackendImClient", diagnosticError);if(!stopped)stop()}
            catch (diagnosticError: LoginRequired) { DiagnosticLog.error("BackendImClient", diagnosticError);
                ++run;authenticated=false;heartbeat?.cancel(false);authDeadline?.cancel(false);retry?.cancel(false);retry=null;socket?.cancel();socket=null;loginFrame=null
                status("账号验证未完成，请重新登录。已开启的通知提醒和定时检查仍可用。")
            }
            catch (diagnosticError: Exception) { DiagnosticLog.error("BackendImClient", diagnosticError);scheduleRetry()}
        } }
    }
    private fun request(client:OkHttpClient,builder:Request.Builder):String {
        guard()
        client.newCall(builder.header("User-Agent","Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36").build()).execute().use { response ->
            if(response.code in setOf(400,401,403) || response.code in 300..399)throw LoginRequired()
            if(!response.isSuccessful)throw IOException("Backend request failed")
            val body=response.body ?: throw IOException("Empty backend reply")
            if(body.source().request(2L*1024*1024+1))throw IOException("Oversized backend reply")
            return body.string()
        }
    }
    private fun connect() {
        guard();heartbeat?.cancel(false);authDeadline?.cancel(false);socket?.cancel();socket=null;authenticated=false;fetched.clear()
        fun authJson(value:String)=try {JSONObject(value)}catch (diagnosticError: Exception) { DiagnosticLog.error("BackendImClient", diagnosticError);throw LoginRequired()}
        val user=authJson(request(web,Request.Builder().url("https://sso.chaoxing.com/apis/login/userLogin4Uname.do"))).optJSONObject("msg") ?: throw LoginRequired()
        val uid=cookies.firstOrNull{it.name in setOf("_uid","UID","uid")}?.value.orEmpty()
        if(uid.isBlank() || uid !in setOf(user.optString("uid"),user.optString("puid")))throw LoginRequired()
        val account=user.optJSONObject("accountInfo")?.optJSONObject("imAccount") ?: throw LoginRequired()
        val username=account.optString("username");val password=account.optString("password")
        if(!Regex("[A-Za-z0-9_@.-]{1,100}").matches(username) || password.isBlank() || password.length>4096 || password.all{it=='*' || it=='•'})throw LoginRequired()
        val body=JSONObject().put("grant_type","password").put("username",username).put("password",password).put("ttl",3600).toString()
        val token=authJson(request(im,Request.Builder().url("https://a1-vip6.easecdn.com/cx-dev/cxstudy/token")
            .post(body.toRequestBody("application/json".toMediaType())))).optString("access_token")
        if(token.isBlank() || token.length>16384)throw LoginRequired()
        synchronized(this) {
        guard()
        loginFrame=BackendImProtocol.provision(username,token,"webim_${System.currentTimeMillis()}")
        val current=++run
        val url="wss://im-api-vip6-v2.easecdn.com/ws/000/${UUID.randomUUID().toString().replace("-","").take(8)}/websocket"
        status("账号已确认，正在连接消息服务…")
        socket=im.newWebSocket(Request.Builder().url(url).header("Origin","https://im.chaoxing.com").build(),object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {post {if(current==run)handle(webSocket,text)}}
            override fun onMessage(webSocket:WebSocket,bytes:ByteString) {if(bytes.size<=3*1024*1024)onMessage(webSocket,bytes.utf8()) else webSocket.cancel()}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { DiagnosticLog.error("BackendSocket",t); DiagnosticLog.event("BackendSocket", "http=${response?.code}");post{if(current==run)scheduleRetry()}}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { DiagnosticLog.event("BackendSocket", "closed code=$code");post{if(current==run)scheduleRetry()}}
        })
        authDeadline=worker.schedule({post{if(current==run && !authenticated)scheduleRetry()}},30,TimeUnit.SECONDS)
        }
    }
    private fun send(command:Int,payload:ByteArray=byteArrayOf()) {
        guard();if(socket?.send(BackendImProtocol.frame(BackendImProtocol.envelope(command,payload)))!=true)throw IOException("IM send unavailable")
    }
    private fun queue(raw:ByteArray,notice:Boolean=false) {
        val jid=ImPb.read(raw)
        if(jid.text(1) !in setOf("",BackendImProtocol.APP_KEY))return
        val key=accountKey(java.util.Base64.getEncoder().encodeToString(raw));val now=System.currentTimeMillis()
        if(now-fetchWindow>=60_000){fetchWindow=now;fetchCount=0}
        if(fetchCount>=10 || (key in fetched && (!notice || now-fetched.getValue(key)<3000)))return
        fetched[key]=now;while(fetched.size>128)fetched.remove(fetched.keys.first());fetchCount++
        send(0,ImPb.Writer().bytes(3,raw).build()) // Queue only: no Meta/chat body or acknowledgement cursor.
    }
    private fun handle(connection:WebSocket,text:String) {
        guard()
        if(text=="o") {
            val frame=loginFrame ?: throw IOException("Missing login frame")
            connection.send(BackendImProtocol.frame(frame));loginFrame=null;return
        }
        if(text=="h")return
        if(text.startsWith("c")){scheduleRetry();return}
        for(bytes in BackendImProtocol.packets(text)) {
            val packet=ImPb.read(bytes);val payload=packet.bytes(9) ?: continue
            when(packet.number(8).toInt()) {
                3 -> {
                    if(BackendImProtocol.provisionStatus(payload)!=0L)throw LoginRequired()
                    authDeadline?.cancel(false)
                    authenticated=true;failures=0
                    status("消息服务已连上，等待新签到或课堂活动",true)
                    send(1)
                    heartbeat?.cancel(false)
                    heartbeat=worker.scheduleWithFixedDelay({post{if(authenticated)send(1)}},45,45,TimeUnit.SECONDS)
                }
                1 -> if(authenticated)ImPb.read(payload).repeated(2).take(2).forEach { ImPb.read(it).bytes(1)?.let{value->queue(value)} }
                2 -> if(authenticated)ImPb.read(payload).bytes(1)?.let{queue(it,true)}
                0 -> if(authenticated) {
                    val data=ImPb.read(payload)
                    if((data.bytes(1)?.let{ImPb.read(it).number(1)} ?: 0L) != 0L)continue
                    data.repeated(4).take(40).forEach {meta->BackendImProtocol.event(meta)?.let{event->verify(event)}}
                }
            }
        }
    }
    private fun verify(event:ImEvent) {
        val now=System.currentTimeMillis()
        if(!BackendImProtocol.fresh(event,began,now)){DiagnosticLog.event("BackendIM", "event_ignored reason=stale");return}
        if(now-verifyWindow>=60_000){verifyWindow=now;verifyCount=0}
        if(verifyCount>=8){DiagnosticLog.event("BackendIM", "event_deferred reason=verification_rate_limit count=$verifyCount");return}
        eventLock.acquire(60_000)
        try {
        for(id in event.activityIds) {
            guard()
            if(id in verified){DiagnosticLog.event("BackendIM", "event_ignored reason=duplicate activity_ref=${DiagnosticLog.reference(id)}");continue}
            if(verifyCount>=8){DiagnosticLog.event("BackendIM", "event_deferred reason=verification_rate_limit count=$verifyCount");return}
            verifyCount++
            val url="https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo".toHttpUrl().newBuilder().addQueryParameter("activeId",id).build()
            val activity=BackendImProtocol.verified(request(web,Request.Builder().url(url)),id,app.selectedCourses()) ?: continue
            verified.add(id)
            while(verified.size>256)verified.remove(verified.first())
            guard()
            val seenField="backend.seen.$owner"
            val previous=app.prefs.getStringSet(seenField,emptySet()).orEmpty()
            val key=accountKey(activity.key)
            if(key in previous)continue
            app.prefs.edit().putStringSet(seenField,(previous.toList().takeLast(999)+key).toSet())
                .putLong("backend.verifiedAt",now).putString("backend.verifiedOwner",owner).putString("backend.verifiedKind",activity.kind.label)
                .putString("backend.verifiedCourse",activity.course.name).apply()
            status("已收到并核对${activity.kind.label} · ${activity.course.name}",true)
            DiagnosticLog.event("BackendIM", "${DiagnosticLog.activity(activity)} verified=true dispatch=reminder")
            received(activity)
        }
        } finally {synchronized(eventLock){if(eventLock.isHeld)eventLock.release()}}
    }
    private fun scheduleRetry() {
        if(stopped || retry?.isDone==false)return
        authenticated=false;heartbeat?.cancel(false);authDeadline?.cancel(false);socket?.cancel();socket=null;loginFrame=null
        val delay=(5L shl failures.coerceIn(0,6)).coerceAtMost(300);failures++
        DiagnosticLog.event("BackendIM", "retry_scheduled attempt=$failures delay_seconds=$delay", "WARN")
        status("连接断开，${delay} 秒后自动重连。已开启的通知提醒和定时检查继续运行。")
        retry=worker.schedule({retry=null;post{connect()}},delay,TimeUnit.SECONDS)
    }
    @Synchronized override fun stop() {
        if(stopped)return
        stopped=true;run++;authenticated=false;loginFrame=null
        heartbeat?.cancel(false);authDeadline?.cancel(false);retry?.cancel(false)
        val oldSocket=socket;socket=null
        worker.shutdownNow()
        synchronized(eventLock){if(eventLock.isHeld)eventLock.release()}
        status("直接获取签到动态未开启")
        // TLS/WebSocket close can perform socket I/O. Never run it on the UI thread.
        closeNetworkAsync {
            diagnosticRunCatching { oldSocket?.cancel() }
            for(client in listOf(web,im)) {
                diagnosticRunCatching { client.dispatcher.cancelAll() }
                diagnosticRunCatching { client.connectionPool.evictAll() }
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}

internal fun closeNetworkAsync(action:()->Unit) {
    Thread({diagnosticRunCatching(action)},"classbell-network-close").apply {isDaemon=true;start()}
}
