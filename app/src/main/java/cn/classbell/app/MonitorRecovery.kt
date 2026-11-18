package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** OS-managed, inexact backstop; no network, exact alarm, or permanent CPU wake lock. */
object MonitorRecovery {
    const val JOB_ID = 206
    private const val NOTICE_ID = 206
    private const val CHANNEL = "monitor_recovery"
    const val RESTORE = "restore_monitor"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) {DiagnosticLog.event("Recovery", "schedule_skipped reason=already_scheduled");return}
        DiagnosticLog.event("Recovery", "schedule periodic_minutes=15 persisted=true")
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, MonitorRecoveryJob::class.java))
            .setPeriodic(15 * 60_000L).setPersisted(true).build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
            AlertTrace.add(context, "系统未接受后台恢复检查，请检查后台运行设置")
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        ListenerRecovery.cancel()
        clearNotice(context)
    }

    fun restore(context: Context, reason: String) {
        val app = context.applicationContext as BellApp
        DiagnosticLog.event("Recovery", "trigger=${DiagnosticLog.label(reason)} enabled=${app.prefs.getBoolean("monitorEnabled",false)} service_running=${MonitorService.running}")
        if (!app.prefs.getBoolean("monitorEnabled", false)) return
        schedule(app)
        ListenerRecovery.ensure(app)
        if (MonitorService.running) { clearNotice(app); return }
        AlertTrace.add(app, "$reason：尝试恢复已开启的监测")
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java).setAction(RESTORE))
            DiagnosticLog.event("Recovery", "foreground_service_requested=true")
        } catch (diagnosticError: Exception) { DiagnosticLog.error("MonitorRecovery", diagnosticError);
            app.setStatus("系统限制后台恢复，请点运行恢复通知或打开 App；通知使用权无需重新开关")
            AlertTrace.add(app, "系统阻止后台恢复，等待打开 App 后恢复")
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "监测恢复提示", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, NOTICE_ID, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(NOTICE_ID, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("学习通提醒需要恢复运行").setContentText("点击自动恢复，无需重新开关通知使用权")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        }
    }

    fun clearNotice(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) }
}

class MonitorRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if ((application as BellApp).prefs.getBoolean("monitorEnabled", false)) MonitorRecovery.restore(this, "系统恢复检查")
        else MonitorRecovery.cancel(this)
        return false
    }
    override fun onStopJob(params: JobParameters?) = false
}

class MonitorRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as BellApp
        // A local simulation is not a real reminder and must never survive reboot/update.
        AlarmScheduler.cancel(app)
        if (app.simulation.snapshot()?.finished == false) app.simulation.finish("设备重启或应用更新，本地测试已取消。")
        MonitorRecovery.restore(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "开机" else "应用更新")
    }
}
