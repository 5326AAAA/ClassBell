package cn.classbell.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class XxtNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        ListenerRecovery.connected()
        AlertTrace.add(this, "通知监听已连接")
        MonitorRecovery.restore(this, "通知监听重连")
        // Do not enumerate/replay old notifications when access is granted or reconnected.
        MonitorService.listenerChanged()
        (application as BellApp).notifyUi()
    }
    override fun onListenerDisconnected() {
        connected = false
        AlertTrace.add(this, "通知监听已断开，检查授权并自动重连")
        ListenerRecovery.ensure(this)
        MonitorService.listenerChanged()
        (application as BellApp).notifyUi()
        super.onListenerDisconnected()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val app = application as BellApp
        if (!MonitorService.running && !app.prefs.getBoolean("monitorEnabled", false)) {
            if(sbn.packageName==NotificationRules.SOURCE_PACKAGE)DiagnosticLog.event("Notification", "ignored reason=monitor_disabled")
            return
        }
        val sameOwner = if (Build.VERSION.SDK_INT >= 29) sbn.uid == Process.myUid()
            else sbn.user == Process.myUserHandle()
        val isLocalTest = sbn.packageName == packageName && sameOwner &&
            sbn.tag == SimulationNotification.TAG && sbn.id == SimulationNotification.ID
        // Other Apps, including ordinary notifications from this App, are rejected before reading contents.
        if (sbn.packageName != NotificationRules.SOURCE_PACKAGE && !isLocalTest) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val nonce = if (isLocalTest) extras.getString(SimulationNotification.EXTRA_NONCE).orEmpty() else null
        if (isLocalTest && !app.simulation.receive(nonce.orEmpty(), SystemClock.elapsedRealtime())) return
        if (isLocalTest) app.notifyUi()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(200)
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().mapNotNull {
            (it as? Bundle)?.getCharSequence("text")?.toString()?.take(1000)
        }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString().take(1000) }
        val text = NotificationRules.latestText(messages, lines,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().take(2000),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(2000))
        val summary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val kind = NotificationRules.classify(title, text, summary)
        DiagnosticLog.event("Notification", "received source=${if(isLocalTest)"simulation" else "chaoxing"} kind=$kind summary=$summary")
        val classroomEnabled = app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)
        if (kind == ReminderKind.CLASSROOM && !classroomEnabled) {DiagnosticLog.event("Notification", "ignored reason=classroom_disabled");return}
        if (kind == null && (isLocalTest || !classroomEnabled || !NotificationRules.mayInspectUnclassified(title, text, summary) ||
                !NotificationRules.shouldQuery(app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY),
                    app.prefs.getBoolean("loggedIn", false), app.selectedCourses().size))) {
            DiagnosticLog.event("Notification", "ignored reason=unclassified_or_query_disabled summary=$summary classroom=$classroomEnabled logged_in=${app.prefs.getBoolean("loggedIn",false)} verify=${app.prefs.getBoolean("verifyOnNotification",NotificationRules.DEFAULT_VERIFY)} selected_courses=${app.selectedCourses().size}");return
        }
        if (isLocalTest) {
            app.simulation.recognize(nonce.orEmpty(), SystemClock.elapsedRealtime())
            app.notifyUi()
        }
        val whenPosted = notification.`when`.takeIf { it > 0 } ?: sbn.postTime
        AlertTrace.add(this, when {
            kind == null -> "收到未分类学习通通知，等待接口检查"
            isLocalTest -> "通知监听已收到并识别本地测试${kind.label}"
            else -> "通知监听已收到并识别学习通${kind.label}"
        })
        val signal = SignSignal(sbn.key, title, text, whenPosted, sbn.postTime, nonce, kind)
        RealSignDetection.record(this, sbn.packageName, signal)
        RealClassTaskDetection.record(this, sbn.packageName, signal)
        DiagnosticLog.event("Notification", "accepted kind=$kind dispatch=monitor_service")
        MonitorService.deliver(this, signal)
    }
    override fun onDestroy() {
        connected = false
        if ((application as BellApp).prefs.getBoolean("monitorEnabled", false)) ListenerRecovery.ensure(this)
        MonitorService.listenerChanged()
        (application as BellApp).notifyUi()
        super.onDestroy()
    }
    companion object {
        @Volatile var connected = false
            private set
        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, XxtNotificationListener::class.java)
            return if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
            else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
