package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

object SimulationNotification {
    const val TAG = "classbell.local-sign-simulation"
    const val ID = 42
    const val EXTRA_NONCE = "cn.classbell.app.SIMULATION_NONCE"
    private const val CHANNEL = "local-sign-simulation"

    fun publish(context: Context, nonce: String, kind: ReminderKind = ReminderKind.SIGN_IN) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地模拟签到通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "仅用于显式点击的模拟测试，不是学习通服务器发布的签到"
            setSound(null, null); enableVibration(false)
        })
        check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
            "Local simulation notifications are disabled"
        }
        val open = PendingIntent.getActivity(context, 42, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(if (kind == ReminderKind.CLASSROOM) "【本地测试】模拟随堂练习" else "【本地测试】模拟课堂签到")
            .setContentText("模拟${kind.label}已发起 · 测试编号 ${nonce.takeLast(8)}。请返回 App 确认提醒。")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addExtras(Bundle().apply { putString(EXTRA_NONCE, nonce) }).build()
        manager.notify(TAG, ID, notification)
        DiagnosticLog.event("Simulation", "notification_published kind=$kind")
    }
    fun clear(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(TAG, ID) }
}
