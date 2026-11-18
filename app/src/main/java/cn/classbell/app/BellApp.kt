package cn.classbell.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

class BellApp : Application() {
    lateinit var vault: SecureStore
    lateinit var cookies: SessionCookies
    lateinit var api: ChaoxingApi
    lateinit var prefs: android.content.SharedPreferences
    lateinit var courseMemory: CourseMemory
    @Volatile var signSessionGeneration = 0
        private set
    val signing by lazy { SigningActions(this) }
    private val storedLocations by lazy { LocationPresets(vault::read,vault::write,vault::remove) }
    internal var locationRepository:LocationPresets?=null
    val locationPresets get()=locationRepository ?: storedLocations
    val savedSignPhotos by lazy { SavedSignPhotos(this,prefs) }
    val boundAccounts by lazy { BoundAccounts({vault.readStrict("bound.accounts")},{vault.write("bound.accounts",it)}) }
    val savedAccounts by lazy { BoundAccounts({vault.readStrict("saved.accounts")},{vault.write("saved.accounts",it)}) }
    val savedCredentials by lazy { SavedCredentials(vault) }
    lateinit var simulation: SimulationSession
        private set
    private val uiHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val diagnosticSettings = android.content.SharedPreferences.OnSharedPreferenceChangeListener { storage, key ->
        when {
            key in setOf("loggedIn", "monitorEnabled", "backendPush", "classroomEnabled", "verifyOnNotification", "fallbackPolling", "voiceEnabled", "ringEnabled", "interval", "phoneType", "ui.route") ->
                DiagnosticLog.event("Settings", "$key=${storage.all[key]}")
            key == "selected" || key?.startsWith("homework.courses.") == true ->
                DiagnosticLog.event("Courses", "selection_changed kind=${if(key=="selected")"reminder" else "homework"} count=${storage.getStringSet(key,emptySet())?.size ?: 0}")
            key?.startsWith("auto") == true -> DiagnosticLog.event("SigningSettings", "changed key=${key.substringBefore('.')} enabled=${storage.all[key] is Boolean && storage.all[key]==true}")
        }
    }
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(this)
        vault = SecureStore(this)
        cookies = SessionCookies(vault)
        api = ChaoxingApi(cookies)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        diagnosticRunCatching { savedCredentials.migrate(this) }
        prefs.registerOnSharedPreferenceChangeListener(diagnosticSettings)
        // A socket cannot survive process death; never restore a cached "connected" badge.
        prefs.edit().putBoolean("backend.connected",false).putString("backend.status","开始监测后连接学习通消息服务").apply()
        courseMemory = CourseMemory(getSharedPreferences("course_memory", Context.MODE_PRIVATE))
        courseMemory.rememberCurrent(prefs)
        simulation = SimulationSession(prefs)
        if (android.os.Build.VERSION.SDK_INT >= 30) diagnosticRunCatching {
            val last = getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
            if (last != null && last.timestamp > prefs.getLong("lastExitRecorded", 0)) {
                prefs.edit().putLong("lastExitRecorded", last.timestamp).apply()
                AlertTrace.add(this, "上次进程退出 ${AlertTrace.time(last.timestamp)}，系统原因码 ${last.reason}（诊断用）")
            }
        }
        if (!prefs.getBoolean("monitorEnabled", false)) prefs.edit().putString("status", "监测未开启").apply()
    }
    fun addUiListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeUiListener(listener: () -> Unit) { listeners.remove(listener) }
    fun notifyUi() { uiHandler.post { listeners.toList().forEach { it() } } }
    fun setStatus(text: String) { DiagnosticLog.event("AppStatus", text); prefs.edit().putString("status", text).apply(); notifyUi() }
    fun setSignDetectionIgnored(owner:String,activity:SignActivity,ignored:Boolean) {
        signing.ledger.setIgnored(owner,activity,ignored)
        if(ignored) MonitorService.dismissIgnoredActivity(owner,activity.key)
        notifyUi()
    }
    fun courses(): List<Course> {
        val array = diagnosticRunCatching { JSONArray(prefs.getString("courses", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let {
            Course.fromJson(it)
        } }
    }
    @Synchronized fun saveSession(courses: List<Course>, account: String, password: String, remember: Boolean, profile:AccountProfile?=api.verifiedProfile, updateCredentials:Boolean=true) {
        profile?.let {AccountAccess.check(it.name)}
        signSessionGeneration++
        val identity = accountKey(cookies.userId() ?: account)
        if(updateCredentials)savedCredentials.save(identity,account,password,remember)
        val credentials=savedCredentials.read(identity)
        DiagnosticLog.event("Account", "session_saved account=${DiagnosticLog.reference(identity)} courses=${courses.size} remember=$remember")
        courseMemory.activate(prefs, identity, courses)
        vault.write("profile", JSONObject().put("account", credentials?.account.orEmpty()).put("password", credentials?.password.orEmpty())
            .put("remember", credentials!=null).put("name",profile?.name.orEmpty()).toString())
        if(profile!=null && profile.uid==cookies.userId()) {
            vault.write("account.profile.$identity",profile.json().toString())
            diagnosticRunCatching {savedAccounts.bind(profile.name,cookies.snapshot(),courses)}
            prefs.edit().putBoolean("account.remember.$identity",true).apply()
        }
        setStatus(if (courses.isEmpty()) "已登录，接口未返回课程；可尝试官方网页登录" else "已登录，读取到 ${courses.size} 门课程")
    }
    fun displayName():String = diagnosticRunCatching{JSONObject(vault.read("account.profile.${prefs.getString("identity","")}") ?: "{}").optString("name")}.getOrDefault("")
    fun rememberCurrentAccount() {
        if(!prefs.getBoolean("loggedIn",false))return
        val identity=prefs.getString("identity","").orEmpty()
        if(identity.isBlank() || !prefs.getBoolean("account.remember.$identity",true))return
        val snapshot=cookies.snapshot()
        val uid=snapshot.firstOrNull{it.name in setOf("_uid","UID","uid")}?.value ?: return
        if(!SignProtocol.validId(uid) || accountKey(uid)!=identity)return
        val old=diagnosticRunCatching{JSONObject(vault.read("profile") ?: "{}").optString("account")}.getOrDefault("")
        val label=displayName().ifBlank{if(Regex("[0-9]{5,20}").matches(old))"账号 · ${old.takeLast(4)}" else "账号 · 编号尾号${uid.takeLast(4)}"}
        savedAccounts.bind(label,snapshot,courses())
    }
    fun selectedCourses(): List<Course> = courses().filter { it.key in (prefs.getStringSet("selected", emptySet()) ?: emptySet()) }
    fun saveSelectedCourses(keys: Set<String>) { courseMemory.saveSelection(prefs, keys); courses().filter{it.key in keys}.forEach{DiagnosticLog.event("CourseSelection", "${DiagnosticLog.course(it)} selected=true")}; notifyUi() }
    @Synchronized fun forgetCredentials(identity:String) {
        savedCredentials.remove(identity)
        if(identity==prefs.getString("identity","")) {
            val profile=JSONObject(vault.read("profile") ?: "{}")
            vault.write("profile",profile.put("account","").put("password","").put("remember",false).toString())
        }
    }
    @Synchronized fun clearSession() {
        DiagnosticLog.event("Account", "logout")
        signSessionGeneration++
        courseMemory.rememberCurrent(prefs)
        val identity=prefs.getString("identity","").orEmpty()
        if(identity.isNotBlank())savedAccounts.unbind(identity)
        forgetCredentials(identity)
        cookies.clear(); vault.remove("profile")
        courseMemory.detach(prefs)
    }
    fun acknowledge(keys: Collection<String>) {
        val field = "ack.${prefs.getString("identity", "") }"
        val values = (prefs.getStringSet(field, emptySet()) ?: emptySet()).toMutableSet()
        values.addAll(keys)
        // Keep recent acknowledgements bounded without retaining response bodies.
        prefs.edit().putStringSet(field, values.takeLastSet(3000)).apply()
    }
    fun acknowledged(): Set<String> = prefs.getStringSet("ack.${prefs.getString("identity", "")}", emptySet())?.toSet() ?: emptySet()
}

private fun <T> Set<T>.takeLastSet(limit: Int): Set<T> = toList().takeLast(limit).toSet()
