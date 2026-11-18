package cn.classbell.app

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** Rebind only an already-authorized, disconnected listener. Never toggle its component or permission. */
object ListenerRecovery {
    private val handler = Handler(Looper.getMainLooper())
    private var retry: Runnable? = null
    private var lastRequest = -30_000L
    private var attempts = 0
    internal var requestBinding: (ComponentName) -> Unit = { NotificationListenerService.requestRebind(it) }

    fun ensure(context: Context) {
        val app = context.applicationContext as BellApp
        if (!XxtNotificationListener.hasAccess(app) || XxtNotificationListener.connected) { cancel(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequest >= 5_000) {
            lastRequest = now
            attempts++
            diagnosticRunCatching { requestBinding(ComponentName(app, XxtNotificationListener::class.java)) }
                .onSuccess { AlertTrace.add(app, "通知使用权仍有效，已请求自动重连（第 $attempts 次）") }
                .onFailure { AlertTrace.add(app, "通知监听重连请求失败，等待后续恢复") }
        }
        if (retry == null && app.prefs.getBoolean("monitorEnabled", false)) {
            val delay = when { attempts <= 1 -> 5_000L; attempts <= 3 -> 15_000L; else -> 60_000L }
            retry = Runnable { retry = null; ensure(app) }.also { handler.postDelayed(it, delay) }
        }
    }

    fun connected() { cancel(); attempts = 0; lastRequest = -30_000L }
    fun cancel() { retry?.let { handler.removeCallbacks(it) }; retry = null }
}
