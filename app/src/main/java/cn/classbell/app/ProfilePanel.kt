package cn.classbell.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*

class ProfilePanel(
    private val activity: Activity, private val app: BellApp,
    openAccount: () -> Unit, pickCourses: () -> Unit, signing: () -> Unit,
    bindings: () -> Unit, settings: () -> Unit, tools: () -> Unit,
    private val savedCount: () -> Int, scan: () -> Unit
) : LinearLayout(activity) {
    private val name = label("登录学习通", 23, true)
    private val accountStatus = label("", 13)
    private val courseCount = label("", 21, true)
    private val reminderCount = label("", 21, true)
    private val accounts = label("", 21, true)
    private val hero = column()

    init {
        orientation = VERTICAL
        hero.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = ModernUi.shape(activity, Color.WHITE, 22)
        }
        val identity = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(ModernUi.avatar(activity), LayoutParams(dp(56), dp(56)))
        val details = column().apply {
            setPadding(dp(10), 0, dp(6), 0)
            addView(name.apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            addView(accountStatus)
        }
        identity.addView(details, LayoutParams(0, -2, 1f))
        identity.addView(StudyUi.button(activity, "账号中心", action = openAccount).apply {
            contentDescription = "账号中心 / 切换账号"
            ModernUi.button(this, textOnly = true)
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 12f
        }, LayoutParams(-2, -2))
        hero.addView(identity)
        val metrics = LinearLayout(activity)
        for ((value, title) in listOf(courseCount to "我的课程", reminderCount to "提醒课程", accounts to "保存账号")) {
            val metric = column().apply {
                gravity = Gravity.CENTER
                addView(value.apply { gravity = Gravity.CENTER })
                addView(label(title, 12).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
            }
            metrics.addView(metric, LayoutParams(0, -2, 1f))
        }
        hero.addView(metrics, LayoutParams(-1, -2).apply { topMargin = dp(24) })
        addView(hero, LayoutParams(-1, -2))

        val signCard = LinearLayout(activity).apply {
            minimumHeight = dp(96)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(0x35FFFFFF),
                ModernUi.shape(activity, ModernUi.strongPrimary, 20), ModernUi.shape(activity, Color.WHITE, 20))
            isFocusable = true
            contentDescription = "签到中心"
            setLoggedClickListener { signing() }
        }
        signCard.addView(ImageView(activity).apply {
            tag = "profile-sign-icon"
            background = ModernUi.shape(activity, 0x26FFFFFF, 12)
            setImageDrawable(ModernUi.icon(activity, "sign", Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(44), dp(44)))
        signCard.addView(column().apply {
            setPadding(dp(12), 0, dp(6), 0)
            addView(label("签到中心", 19, true).apply { setTextColor(Color.WHITE) })
            addView(label("当前活动与历史结果", 12).apply { setTextColor(0xFFCEE6DA.toInt()) })
        }, LayoutParams(0, -2, 1f))
        signCard.addView(label("›", 21).apply { setTextColor(Color.WHITE) })
        addView(signCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        addView(label("常用工具", 14, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        val quickRow = LinearLayout(activity)
        val shortcuts = listOf(
            shortcut("提醒课程", "courses", pickCourses),
            shortcut("扫码签到", "qr", scan),
            shortcut("关联账号", "profile", bindings)
        )
        shortcuts.forEachIndexed { index, tile ->
            quickRow.addView(tile, LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        addView(quickRow, LayoutParams(-1, -2))

        val links = LinearLayout(activity)
        for ((title, action) in listOf("提醒设置" to settings, "更多工具" to tools)) {
            val start = title == "提醒设置"
            links.addView(StudyUi.button(activity, title, action = action).apply {
                ModernUi.button(this, textOnly = true)
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL or (if (start) Gravity.START else Gravity.END)
                setPadding(dp(4), 0, dp(4), 0)
            }, LayoutParams(0, -2, 1f))
        }
        addView(links, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(View(activity), LayoutParams(-1, 0, 1f))
        addView(label("Class Bell ${BuildConfig.VERSION_NAME}\n作者：Forest5326", 12).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(16), 0, dp(8))
        }, LayoutParams(-1, -2))
        render()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // On smaller screens or with large text, the page can grow and scroll.
        val viewport = MeasureSpec.getSize(heightMeasureSpec)
        val minimum = maxOf(dp(154), viewport / 2 - dp(108))
        if (hero.minimumHeight != minimum) hero.minimumHeight = minimum
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun render() {
        val logged = app.prefs.getBoolean("loggedIn", false)
        name.text = if (logged) app.displayName().ifBlank { "学习通" } else "登录学习通"
        accountStatus.text = when {
            !logged -> "登录后查看课程与作业"
            MonitorService.running -> "课堂提醒运行中"
            else -> "已登录学习通"
        }
        courseCount.text = (if (logged) app.courses().size else 0).toString()
        reminderCount.text = (if (logged) app.selectedCourses().size else 0).toString()
        accounts.text = savedCount().toString()
    }

    private fun shortcut(title: String, icon: String, action: () -> Unit): LinearLayout {
        val tile = column().apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(84)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isFocusable = true
            contentDescription = title
            setLoggedClickListener { action() }
            background = RippleDrawable(ColorStateList.valueOf(0x16146B53), ModernUi.shape(activity, Color.WHITE, 16),
                ModernUi.shape(activity, Color.WHITE, 16))
        }
        tile.addView(ImageView(activity).apply {
            setImageDrawable(ModernUi.icon(activity, icon, ModernUi.primary))
            background = ModernUi.shape(activity, ModernUi.soft, 10)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(32), dp(32)))
        tile.addView(label(title, 14, true).apply { gravity = Gravity.CENTER },
            LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return tile
    }

    private fun column() = LinearLayout(activity).apply { orientation = VERTICAL }
    private fun dp(value: Int) = ModernUi.dp(activity, value)
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(if (bold) ModernUi.ink else ModernUi.muted)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
