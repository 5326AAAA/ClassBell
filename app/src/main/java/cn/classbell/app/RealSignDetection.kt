package cn.classbell.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A receipt for an OS notification callback, never evidence of a completed task. */
open class RealNotificationDetection(private val prefix: String, private val kind: ReminderKind,
                                     private val notDetected: String) {
    private val RECEIVED = "$prefix.receivedAt"
    private val TITLE = "$prefix.title"
    fun record(context: Context, sourcePackage: String, signal: SignSignal): Boolean {
        val app = context.applicationContext as BellApp
        if (sourcePackage != NotificationRules.SOURCE_PACKAGE || signal.simulationId != null ||
            signal.kind != kind || NotificationRules.classify(signal.title, signal.text) != kind ||
            signal.postedAt < app.prefs.getLong("monitorStartedAt", 0)) return false
        app.prefs.edit().putLong(RECEIVED, System.currentTimeMillis())
            .putString(TITLE, signal.title.take(120)).apply()
        DiagnosticLog.event("NotificationReceipt", "recorded kind=$kind")
        app.notifyUi()
        return true
    }
    fun receivedAt(context: Context) = (context.applicationContext as BellApp).prefs.getLong(RECEIVED, 0)
    fun description(context: Context): String {
        val app = context.applicationContext as BellApp
        val at = receivedAt(context)
        if (at == 0L) return notDetected
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(at))
        val title = app.prefs.getString(TITLE, "").orEmpty()
        return listOf("已检测到 · 通知来源：学习通 App", "最近收到：$time（北京时间）", "通知标题：$title")
            .filterNot { it == "通知标题：" }.joinToString("\n")
    }
    fun clear(context: Context) {
        DiagnosticLog.event("NotificationReceipt", "cleared kind=$kind")
        val app = context.applicationContext as BellApp
        app.prefs.edit().remove(RECEIVED).remove(TITLE).apply()
        app.notifyUi()
    }
}

object RealSignDetection : RealNotificationDetection("realSign", ReminderKind.SIGN_IN, "尚未检测到真实学习通签到通知")

object RealClassTaskDetection : RealNotificationDetection("realClassTask", ReminderKind.CLASSROOM, "尚未检测到真实学习通课堂任务通知")
