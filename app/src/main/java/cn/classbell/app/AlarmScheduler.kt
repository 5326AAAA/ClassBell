package cn.classbell.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only explicitly requested one-shot tests use exact alarms. Idle monitoring never schedules one. */
object AlarmScheduler {
    const val EXTRA_NONCE = "test_nonce"
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun operation(context: Context, nonce: String = "") = PendingIntent.getBroadcast(context, 80,
        Intent(context, TestAlarmReceiver::class.java).putExtra(EXTRA_NONCE, nonce),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, nonce: String, delayMs: Long) {
        check(allowed(context)) { "请先允许闹钟和提醒权限，再重试 10 秒测试。" }
        val app = context.applicationContext as BellApp
        val due = System.currentTimeMillis() + delayMs
        val show = PendingIntent.getActivity(context, 80, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        app.prefs.edit().putString("scheduledTestNonce", nonce).putLong("scheduledTestDue", due).commit()
        try {
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(due, show), operation(context, nonce))
            AlertTrace.add(context, "已交给系统闹钟，计划 ${AlertTrace.time(due)} 执行")
        } catch (e: Exception) { DiagnosticLog.error("AlarmScheduler", e);
            app.prefs.edit().remove("scheduledTestNonce").remove("scheduledTestDue").commit()
            throw e
        }
    }
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
        (context.applicationContext as BellApp).prefs.edit().remove("scheduledTestNonce").remove("scheduledTestDue").apply()
    }
}

class TestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BellApp
        val nonce = intent.getStringExtra(AlarmScheduler.EXTRA_NONCE) ?: return
        if (nonce != app.prefs.getString("scheduledTestNonce", null)) return
        val due = app.prefs.getLong("scheduledTestDue", 0)
        app.prefs.edit().remove("scheduledTestNonce").remove("scheduledTestDue").commit()
        AlertTrace.add(context, "系统闹钟已回调，较计划晚 ${((System.currentTimeMillis() - due).coerceAtLeast(0)) / 1000} 秒")
        if (!app.simulation.active(nonce, android.os.SystemClock.elapsedRealtime())) {
            app.simulation.finish("系统闹钟回调时测试已过期；请保留诊断记录，检查省电和后台设置。")
            AlertTrace.add(context, "回调已到达，但测试会话已过期，未再响铃")
            return
        }
        try {
            // A user-requested exact alarm is a documented background FGS start exemption.
            context.startForegroundService(Intent(context, MonitorService::class.java).setAction(MonitorService.FIRE_TEST)
                .putExtra(AlarmScheduler.EXTRA_NONCE, nonce))
        } catch (diagnosticError: Exception) { DiagnosticLog.error("AlarmScheduler", diagnosticError);
            app.simulation.finish("系统闹钟已执行，但系统阻止启动提醒服务；请检查后台运行设置。")
            AlertTrace.add(context, "系统阻止从闹钟启动提醒服务")
            app.notifyUi()
        }
    }
}

/** Local bounded phase log: no credentials, notification bodies or course information. */
object AlertTrace {
    fun time(value: Long): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(value))
    @Synchronized fun add(context: Context, phase: String) {
        DiagnosticLog.event("Reminder", phase.substringBefore(" · "))
        val app = context.applicationContext as BellApp
        val old = diagnosticRunCatching { JSONArray(app.prefs.getString("alertTrace", "[]")) }.getOrDefault(JSONArray())
        val lines = (0 until old.length()).map { old.optString(it) }.takeLast(19)
        val screen = context.getSystemService(PowerManager::class.java).isInteractive
        val next = JSONArray(lines + "${time(System.currentTimeMillis())} [${if (screen) "亮屏" else "息屏"}] $phase")
        app.prefs.edit().putString("alertTrace", next.toString()).commit()
        app.notifyUi()
    }
    fun description(context: Context): String {
        val app = context.applicationContext as BellApp
        val lines = diagnosticRunCatching { JSONArray(app.prefs.getString("alertTrace", "[]")) }.getOrDefault(JSONArray())
        return (0 until lines.length()).joinToString("\n") { lines.optString(it) }.ifEmpty { "尚无提醒记录。" }
    }
}
