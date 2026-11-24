package cn.classbell.app

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug source set only. No socket, executable commands, credentials or sign-in operations. */
class CodexDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("requestId").orEmpty()
        if (!BuildConfig.DEBUG || context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            reply(403, "debug_build_required")
            return
        }
        if (intent.action != ACTION || intent.getStringExtra("operation") !in setOf("status", "ping") ||
            !Regex("[a-f0-9]{32}").matches(requestId)) {
            reply(400, "invalid_debug_command")
            return
        }
        try {
            val text = CodexDebugSnapshot.collect(context).put("requestId", requestId).toString(2)
            val directory = File(context.filesDir, "codex-debug").apply { check(isDirectory || mkdirs()) }
            val target = AtomicFile(File(directory, "status.json"))
            val stream = target.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
            reply(200, "codex_debug_ready:$requestId")
        } catch (error: Exception) {
            DiagnosticLog.error("CodexDebug", error)
            reply(500, "debug_snapshot_failed:${error.javaClass.simpleName}")
        }
    }

    private fun reply(code: Int, message: String) { if (isOrderedBroadcast) setResult(code, message, null) }

    companion object { const val ACTION = "cn.classbell.app.debug.COMMAND" }
}

internal object CodexDebugSnapshot {
    fun collect(context: Context): JSONObject {
        val app = context.applicationContext as BellApp
        val runtime = Runtime.getRuntime()
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val permissions = JSONObject()
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS).forEach {
            permissions.put(it.substringAfterLast('.'), context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
        }
        val prefs = JSONObject()
        val storedSettings = app.prefs.all
        listOf("loggedIn", "monitorEnabled", "backendPush", "backendConnected", "fallbackPolling",
            "classroomEnabled", "verifyOnNotification", "voiceEnabled", "ringEnabled").forEach {
            prefs.put(it, storedSettings[it] as? Boolean ?: false)
        }
        prefs.put("selectedCourseCount", (storedSettings["selected"] as? Set<*>)?.size ?: 0)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.getNetworkCapabilities(manager.activeNetwork)
        val transports = JSONArray()
        listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn").forEach { (id, name) ->
            if (network?.hasTransport(id) == true) transports.put(name)
        }
        val listenerEnabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { it.substringBefore('/') == context.packageName }
        return JSONObject()
            .put("schemaVersion", 1).put("capturedAtEpochMs", System.currentTimeMillis())
            .put("app", JSONObject().put("package", context.packageName).put("version", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE).put("debuggable", true))
            .put("bridge", JSONObject().put("transport", "adb").put("commands", JSONArray(listOf("ping", "status")))
                .put("rootRequired", false).put("networkListener", false).put("mutatesAccountOrSignState", false))
            .put("device", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL))
            .put("process", JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("deviceUptimeMs", SystemClock.elapsedRealtime()))
            .put("permissions", permissions).put("settings", prefs)
            .put("background", JSONObject().put("notificationsEnabled", notifications.areNotificationsEnabled())
                .put("notificationListenerEnabled", listenerEnabled).put("interactive", power.isInteractive)
                .put("powerSaveMode", power.isPowerSaveMode)
                .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(context.packageName))
                .put("exactAlarmsAllowed", if (Build.VERSION.SDK_INT >= 31)
                    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true))
            .put("network", JSONObject().put("connected", network != null).put("transports", transports)
                .put("validated", network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
            .put("memory", JSONObject().put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("heapLimitBytes", runtime.maxMemory()).put("deviceAvailableBytes", memory.availMem).put("deviceLowMemory", memory.lowMemory))
            .put("storage", JSONObject().put("appVolumeUsableBytes", context.filesDir.usableSpace))
    }
}
