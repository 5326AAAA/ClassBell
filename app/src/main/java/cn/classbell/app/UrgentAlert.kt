package cn.classbell.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

object UrgentAlert {
    const val CHANNEL = "urgent-sign-alarm-v1"
    const val ID = 81
    const val EXTRA_TOKEN = "alert_token"
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "签到与课堂活动紧急提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "签到或随堂练习时弹出锁屏提醒；循环铃声和语音由提醒服务播放，使用闹钟音量"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null); enableVibration(false)
            })
    }
    fun fullScreenAllowed(context: Context) = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun publish(context: Context, kinds: Set<ReminderKind> = setOf(ReminderKind.SIGN_IN)) {
        val app = context.applicationContext as BellApp
        app.prefs.edit().putStringSet("activeAlertKinds", kinds.map { it.name }.toSet()).apply()
        ensureChannel(context)
        val token = app.prefs.getString("activeAlertToken", null) ?: UUID.randomUUID().toString().also {
            app.prefs.edit().putString("activeAlertToken", it).commit()
        }
        val open = PendingIntent.getActivity(context, ID, Intent(context, AlertActivity::class.java)
            .putExtra(EXTRA_TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ack = PendingIntent.getBroadcast(context, ID, Intent(context, AlertActionReceiver::class.java)
            .putExtra(EXTRA_TOKEN, token), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title(context)).setContentText("请打开学习通查看；点“我已看到”停止铃声和振动")
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "我已看到 · 停止提醒", ack).build())
        if (fullScreenAllowed(context)) builder.setFullScreenIntent(open, true)
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                AlertTrace.add(context, "本 App 通知或紧急提醒分类被关闭，无法弹出")
            }
            manager.notify(ID, builder.build())
        } catch (diagnosticError: Exception) { DiagnosticLog.error("UrgentAlert", diagnosticError); AlertTrace.add(context, "系统拒绝显示紧急提醒通知，请检查通知授权") }
    }
    fun clear(context: Context) {
        (context.applicationContext as BellApp).prefs.edit().remove("activeAlertToken").remove("activeAlertKinds").apply()
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun valid(context: Context, token: String?): Boolean = token != null &&
        token == (context.applicationContext as BellApp).prefs.getString("activeAlertToken", null)
    fun title(context: Context): String {
        val names = (context.applicationContext as BellApp).prefs.getStringSet("activeAlertKinds", emptySet()).orEmpty()
        val labels = ReminderKind.entries.filter { it.name in names }.joinToString(" / ") { it.label }
        return "${labels.ifBlank { "学习通" }}提醒"
    }
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MonitorService.STOP) { MonitorService.stopMonitoring(context); return }
        if (!UrgentAlert.valid(context, intent.getStringExtra(UrgentAlert.EXTRA_TOKEN))) return
        MonitorService.acknowledge(context)
    }
}

/** An OS-mediated full-screen notification destination; it never dismisses the user's keyguard. */
class AlertActivity : Activity() {
    private val app get() = application as BellApp
    private lateinit var headline: TextView
    private val listener: () -> Unit = {
        if (!UrgentAlert.valid(this, intent.getStringExtra(UrgentAlert.EXTRA_TOKEN))) finish()
        else if (::headline.isInitialized) headline.text = UrgentAlert.title(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UrgentAlert.valid(this, intent.getStringExtra(UrgentAlert.EXTRA_TOKEN))) { finish(); return }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(36)); setBackgroundColor(ModernUi.background)
        }
        headline = TextView(this).apply { text = UrgentAlert.title(this@AlertActivity); textSize = 34f; gravity = Gravity.CENTER; setTextColor(ModernUi.primary); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        body.addView(headline)
        body.addView(TextView(this).apply {
            text = "请到学习通查看并完成签到或随堂练习"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(ModernUi.muted); setPadding(0, dp(28), 0, dp(28))
        })
        body.addView(Button(this).apply {
            text = "我已看到 · 停止铃声和振动"
            ModernUi.button(this, primaryAction = true)
            setLoggedClickListener { MonitorService.acknowledge(this@AlertActivity); finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(Button(this).apply {
            text = "打开提醒 App"
            ModernUi.button(this)
            setLoggedClickListener { startActivity(Intent(this@AlertActivity, MainActivity::class.java)); finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(body)
        AlertTrace.add(this, "锁屏提醒页已创建")
    }
    override fun onStart() {
        super.onStart(); app.addUiListener(listener)
    }
    override fun onResume() {
        super.onResume()
        if (UrgentAlert.valid(this, intent.getStringExtra(UrgentAlert.EXTRA_TOKEN))) {
            // A visible activity can recover an event whose background FGS start was denied.
            MonitorService.resumeQueued(this)
        }
    }
    override fun onStop() { app.removeUiListener(listener); super.onStop() }
}
