package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Read-only permission overview. Optional features do not imply mandatory permissions. */
class FeaturePermissionsPanel(private val activity: Activity) : LinearLayout(activity) {
    private val monitor = text("", 11)
    private val notification = text("", 11)
    private val backgroundState = text("", 11)
    private val camera = text("", 11)
    private val fullscreen = text("", 11)
    private val alarm = text("", 11)
    private val extra = LinearLayout(activity).apply { orientation = VERTICAL; visibility = View.GONE }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(8))
        background = ModernUi.shape(activity, ModernUi.soft, 14)
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("功能权限", 13, true))
        heading.addView(monitor.apply { setPadding(dp(8), 0, dp(4), 0) }, LayoutParams(0, -2, 1f))
        heading.addView(StudyUi.button(activity, "查看全部") {
            extra.visibility = if (extra.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val toggle = heading.getChildAt(2) as android.widget.Button
            toggle.text = if (extra.visibility == View.VISIBLE) "收起" else "查看全部"
        }.apply {
            ModernUi.button(this, textOnly = true)
            textSize = 11f
            minWidth = 0
            setPadding(dp(4), 0, 0, 0)
        }, LayoutParams(-2, dp(48)))
        addView(heading, LayoutParams(-1, -2))
        addRow(this, "提醒通知", notification)
        addRow(this, "后台运行", backgroundState)
        addRow(this, "扫码拍照", camera)
        addRow(extra, "锁屏提醒", fullscreen)
        addRow(extra, "定时测试", alarm)
        addRow(extra, "资料下载", text("仅访问所选文件夹，无需全部文件权限", 11))
        addRow(extra, "位置签到", text("暂不可用，无需提前开启定位", 11))
        addView(extra, LayoutParams(-1, -2))
    }

    fun render() {
        val running = MonitorService.running && !MonitorService.simulationOnly
        monitor.text = when {
            MonitorService.running && MonitorService.simulationOnly -> "本地测试中"
            running -> "监测运行中"
            else -> "监测未开启"
        }
        monitor.setTextColor(if (running) ModernUi.primary else ModernUi.muted)
        val manager = activity.getSystemService(NotificationManager::class.java)
        notification.text = states(
            allowed("通知", manager.areNotificationsEnabled()),
            allowed("通知使用权", XxtNotificationListener.hasAccess(activity)))
        backgroundState.text = states(
            allowed("电池优化例外", activity.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(activity.packageName)),
            "自启动需手动核对" to ModernUi.muted)
        camera.text = states(
            allowed("相机", activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            "相册按次选择" to ModernUi.muted)
        fullscreen.text = states(allowed("全屏通知", UrgentAlert.fullScreenAllowed(activity)),
            "横幅/锁屏需核对" to ModernUi.muted)
        alarm.text = states(allowed("精确闹钟", AlarmScheduler.allowed(activity)),
            "仅10秒测试需要" to ModernUi.muted)
    }

    private fun allowed(name: String, granted: Boolean) =
        "$name${if (granted) "已允许" else "未允许"}" to
            if (granted) ModernUi.primary else ModernUi.ChoiceTone.INCOMPLETE.text

    private fun states(vararg entries: Pair<String, Int>) = SpannableStringBuilder().apply {
        entries.forEachIndexed { index, (value, color) ->
            if (index > 0) append(" · ")
            val start = length
            append(value)
            setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun addRow(parent: LinearLayout, title: String, value: TextView) {
        parent.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            val titleWidth = (62 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
            addView(text(title, 11, true), LayoutParams(dp(titleWidth), -2))
            addView(value, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2))
    }

    private fun text(value: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        includeFontPadding = false
        setTextColor(if (bold) ModernUi.ink else ModernUi.muted)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun dp(value: Int) = ModernUi.dp(activity, value)
}
