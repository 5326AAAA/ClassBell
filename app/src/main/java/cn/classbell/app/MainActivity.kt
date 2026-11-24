package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val app get() = application as BellApp
    private val worker = Executors.newSingleThreadExecutor()
    private val teal = ModernUi.primary
    private val ink = ModernUi.ink
    private val muted = ModernUi.muted
    private val pages = mutableListOf<ViewGroup>()
    private val navigation = mutableListOf<Button>()
    private val routes = listOf("home", "courses", "homework", "test", "settings","profile","account","signing","monitor-courses","receipts","tools")
    private val primaryPages=listOf(0,1,2,5)
    private val history=java.util.ArrayDeque<Int>()
    private lateinit var back:ImageButton
    private lateinit var pageTitle:TextView
    private lateinit var credits:LinearLayout
    private lateinit var courseListPanel:CourseListPanel
    private lateinit var accountSwitcher:AccountSwitcherPanel
    private lateinit var loginCard:LinearLayout
    private lateinit var profilePanel:ProfilePanel
    internal var savedAccountRepository:BoundAccounts?=null
    private val accountShelf get()=savedAccountRepository ?: app.savedAccounts
    private lateinit var homeworkPanel: HomeworkPanel
    private lateinit var signingPanel: SigningPanel
    private var currentPage = 0
    private lateinit var monitorBadge: TextView
    private lateinit var monitorTitle: TextView
    private lateinit var monitorSummary: TextView
    private lateinit var homeHealth: TextView
    private lateinit var acknowledgeButton: Button
    private val phoneFamily get() = BackgroundSettings.effectiveFamily(app.prefs)
    private lateinit var status: TextView
    private lateinit var realSignStatus: TextView
    private lateinit var realClassTaskStatus: TextView
    private lateinit var alert: TextView
    private lateinit var health: TextView
    private lateinit var notificationHealth: TextView
    private lateinit var backgroundTitle: TextView
    private lateinit var backgroundSummary: TextView
    private lateinit var phoneType: Spinner
    private lateinit var xiaomiPermissions: Button
    private lateinit var courseLabel: TextView
    private lateinit var account: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var login: Button
    private lateinit var web: Button
    private lateinit var refresh: Button
    private lateinit var select: Button
    private lateinit var start: Button
    private lateinit var logout: Button
    private lateinit var interval: Spinner
    private lateinit var verify: CheckBox
    private lateinit var polling: CheckBox
    private lateinit var mode: TextView
    private lateinit var backendPush: CheckBox
    private lateinit var backendStatus: TextView
    private lateinit var authStatus: TextView
    private lateinit var authForm: LinearLayout
    private lateinit var authFormToggle: Button
    private var authFormExpanded = false
    private var lastLoginState = false
    private var accountActions:AlertDialog?=null
    private var helpPrompt:AlertDialog?=null
    private lateinit var authProgress: ProgressBar
    private lateinit var cancelOperation: Button
    private lateinit var voice: CheckBox
    private lateinit var ring: CheckBox
    private lateinit var classroom: CheckBox
    private lateinit var trace: TextView
    private lateinit var receiptTrace: TextView
    private lateinit var simulationStatus: TextView
    private var simulationDelayMs = 0L
    private var simulationKind = ReminderKind.SIGN_IN
    private var rendering = false
    private var busy = false
    private val operationGuard = Any()
    @Volatile private var operationGeneration = 0
    private var operationTask: Future<*>? = null
    private val listener: () -> Unit = { renderState() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shell = column().apply { setBackgroundColor(ModernUi.background) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(shell)
        credits = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(10), dp(22), 0)
        }
        credits.addView(label("作者：Forest5326", 12, muted), LinearLayout.LayoutParams(0, -2, 1f))
        credits.addView(label("v${BuildConfig.VERSION_NAME}", 12, muted))
        shell.addView(credits)
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(2), dp(14), dp(8))
        }
        back=ModernUi.navigation(this){onBackPressed()};toolbar.addView(back,LinearLayout.LayoutParams(dp(48),dp(48)))
        pageTitle=label("Class Bell",24,ink,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        toolbar.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val help = button("☰ 使用帮助") {}
        help.layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.START }
        ModernUi.button(help, textOnly = true)
        help.textSize = 12f
        help.setLoggedClickListener { showHelpMenu(help) }
        toolbar.addView(help); shell.addView(toolbar)
        val pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = page(pageHost, "课堂提醒", "签到、随堂练习，分别用语音提醒。", "签到，及时知道。")
        val courseBody = page(pageHost, "课程", "进入课程，查看签到、作业与学习内容。")
        val homeworkBody = page(pageHost, "作业", "一键查看待交、已交和已过期作业。")
        val testBody = page(pageHost, "测试与诊断", "先测试提醒，再放心上课。")
        val settingsBody = page(pageHost, "提醒设置", "调整提醒，检查后台权限。")
        val myBody=page(pageHost,"我的","",fillViewport=true)
        val accountBody=page(pageHost,"账号中心","登录、添加账号，或切换已保存的账号。")
        val signingBody=page(pageHost,"签到中心","")
        val monitorBody=page(pageHost,"监测课程","选择收到哪些课程的提醒。")
        val receiptBody=page(pageHost,"提醒记录","查看学习通通知和每次提醒的触发过程。")
        val toolsPage=pages.size
        val toolsBody=page(pageHost,"更多工具","")
        homeworkPanel = HomeworkPanel(this, app, { showPage(6) })
        homeworkBody.addView(homeworkPanel)
        courseListPanel=CourseListPanel(this,app,{if(app.prefs.getBoolean("loggedIn",false))reloadCourses() else showPage(6)},{showPage(6)})
        courseBody.addView(courseListPanel)
        myBody.removeAllViews()
        profilePanel=ProfilePanel(this,app,
            openAccount={showPage(6)},
            pickCourses={startActivity(Intent(this,CoursePickerActivity::class.java))},
            signing={showPage(7)},
            bindings={startActivity(Intent(this,BoundAccountsActivity::class.java))},
            settings={showPage(4)},
            tools={showPage(toolsPage)},
            savedCount={accountShelf.list().size},
            scan={if(app.prefs.getBoolean("loggedIn",false))startActivity(Intent(this,QrScanActivity::class.java)) else showPage(6)})
        // The profile owns one content scroller and a fixed footer.
        val oldProfilePage = myBody.parent as ScrollView
        val profileIndex = pages.indexOf(oldProfilePage)
        pageHost.removeView(oldProfilePage)
        profilePanel.setPadding(dp(20),dp(8),dp(20),dp(8))
        pageHost.addView(profilePanel,FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels,dp(640)),-1,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pages[profileIndex] = profilePanel
        toolsBody.addView(profileAction("运行日志与故障反馈","courses"){
            startActivity(Intent(this,DiagnosticActivity::class.java))
        })
        toolsBody.addView(profileAction("提醒记录","homework"){showPage(9)})
        toolsBody.addView(profileAction("测试与诊断","test"){showPage(3)})

        val monitor = card(body)
        monitor.background = ModernUi.shape(this, teal, 26)
        val monitorHeading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        monitorTitle=label("通知监测",13,Color.WHITE,true)
        monitorHeading.addView(monitorTitle, LinearLayout.LayoutParams(0, -2, 1f))
        monitorBadge = label("未开启", 12, Color.WHITE, true).apply {
            background = ModernUi.shape(this@MainActivity, 0x28FFFFFF, 12)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        monitorHeading.addView(monitorBadge); monitor.addView(monitorHeading)
        status = label("监测未开启", 24, Color.WHITE, true).apply { setPadding(0, dp(16), 0, dp(6)) }
        monitor.addView(status)
        monitorSummary=label("接收学习通通知，签到与课堂任务分别提醒。",13,0xE6FFFFFF.toInt())
        monitor.addView(monitorSummary)
        alert = label("", 16, Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(8)) }
        monitor.addView(alert)
        start = button("开始通知监测", true) { startMonitoring() }
        ModernUi.button(start, primaryAction = true, inverted = true)
        val repair = button("修复通知连接") { repairListener() }
        val stop = button("停止监测") { haltMonitoring(); toast("监测已停止，可登录或修改设置"); renderState() }
        stop.background = ModernUi.shape(this, 0x24FFFFFF, 14); stop.setTextColor(Color.WHITE)
        pair(monitor, start, stop, 2f)
        acknowledgeButton = button("停止声音和振动 · 我已看到") { MonitorService.acknowledge(this) }.apply {
            ModernUi.button(this, textOnly = true); setTextColor(Color.WHITE)
        }
        monitor.addView(acknowledgeButton)
        homeHealth = label("", 12, muted).apply { setPadding(dp(2), dp(8), dp(2), dp(8)); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL }
        homeHealth.setLoggedClickListener { showPage(4) }
        homeHealth.contentDescription = "查看通知连接与后台权限设置"
        val connection = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        connection.addView(homeHealth, LinearLayout.LayoutParams(0, -2, 1f))
        ModernUi.button(repair, textOnly = true); repair.textSize = 12f
        connection.addView(repair, LinearLayout.LayoutParams(-2, -2)); body.addView(connection)

        val receipt = card(receiptBody)
        receiptHeading(receipt, "真实学习通签到检测", "清除真实签到检测记录") {
            RealSignDetection.clear(this); toast("检测记录已清除，监测开关不变")
        }
        realSignStatus = label("", 14, teal).apply { setTextIsSelectable(true) }
        receipt.addView(realSignStatus)

        val taskReceipt = card(receiptBody)
        receiptHeading(taskReceipt, "学习通真实课堂任务检测", "清除真实课堂任务检测记录") {
            RealClassTaskDetection.clear(this); toast("课堂任务检测记录已清除，监测开关不变")
        }
        realClassTaskStatus = label("", 14, teal).apply { setTextIsSelectable(true) }
        taskReceipt.addView(realClassTaskStatus)
        val diagnostics = card(receiptBody)
        diagnostics.addView(label("提醒诊断", 19, ink, true))
        diagnostics.addView(label("最近 20 步。可查看活动来源、未重复提醒的原因和铃声状态。", 12, muted))
        receiptTrace = label("", 13, ink).apply { setTextIsSelectable(true) }
        diagnostics.addView(receiptTrace)
        diagnostics.addView(button("复制提醒诊断记录") { copyReminderTrace() })
        pair(body,button("课程与签到"){showPage(1)},button("作业清单"){showPage(2)})
        body.addView(button("查看真实提醒记录"){showPage(9)})

        val soundTest = compactSection(testBody,"快速测试","声音、振动与语音",true)
        soundTest.addView(label("声音与振动", 19, ink, true))
        soundTest.addView(label("使用闹钟音量。提醒发出后，可点“我已看到”停止。", 13, muted))
        soundTest.addView(button("测试提醒 · 5 秒") { requestNotificationsThen(MonitorService.TEST) })
        soundTest.addView(button("测试课堂活动语音 · 5 秒") { requestNotificationsThen(MonitorService.TEST_CLASSROOM) })
        val locationPractice=card(testBody)
        locationPractice.addView(label("位置签到模拟",19,ink,true))
        locationPractice.addView(label("在示意地图上手动选点，体验范围判断。无需登录或定位权限。",13,muted))
        locationPractice.addView(button("打开位置签到模拟"){startActivity(Intent(this,LocationDemoActivity::class.java))})
        locationPractice.addView(button("手动选点 / 经纬度预览"){if(app.prefs.getBoolean("loggedIn",false))startActivity(Intent(this,LocationPickerActivity::class.java)) else toast("请先登录账号。")})
        val qrPractice=card(testBody)
        qrPractice.addView(label("二维码签到与识别",19,ink,true))
        qrPractice.addView(label("正式签到在 ClassBell 内核对课程、在线提交并显示结果。网页入口仅测试识别。",13,muted))
        qrPractice.addView(button("打开应用内扫码签到"){if(app.prefs.getBoolean("loggedIn",false))startActivity(Intent(this,QrScanActivity::class.java)) else toast("请先在账号中心登录。")})
        qrPractice.addView(button("网页扫码测试（不提交）"){if(app.prefs.getBoolean("loggedIn",false))startActivity(Intent(this,LocalScanWebActivity::class.java)) else toast("请先在账号中心登录。")})
        qrPractice.addView(button("预览无签名签到链接") {
            val preview = Uri.Builder().scheme("https").authority("example.invalid")
                .appendPath("attendance").appendPath("preview")
                .appendQueryParameter("activity", "demo-001").build()
            showInfo("签到链接预览", "示例课程 · 示例签到\n\n$preview\n\n这个示例只在本机展示，用于检查链接格式。")
        })
        val simulation = compactSection(testBody,"模拟与诊断","需要排查提醒时再展开")
        simulation.addView(label("通知模拟", 19, ink, true))
        simulation.addView(label("检查通知接收、内容识别和提醒流程，不向学习通发布活动。", 13, muted))
        simulationStatus = label("尚未开始本地模拟测试。", 14, teal).apply { setTextIsSelectable(true) }
        simulation.addView(simulationStatus)
        simulation.addView(button("立即模拟发布签到", true) { simulateNotification(0) })
        simulation.addView(button("10 秒后模拟签到 · 可锁屏") { simulateNotification(10_000) })
        simulation.addView(button("立即模拟课堂活动") { simulateNotification(0, ReminderKind.CLASSROOM) })
        simulation.addView(button("10 秒后模拟课堂活动 · 可锁屏") { simulateNotification(10_000, ReminderKind.CLASSROOM) })
        simulation.addView(button("10 秒后锁屏闹铃测试（不依赖学习通）") {
            if (requireExactAlarm()) requestNotificationsThen(MonitorService.ALARM_TEST)
        })
        simulation.addView(button("取消模拟测试") {
            if (app.simulation.snapshot()?.finished == false) {
                AlarmScheduler.cancel(this)
                if (MonitorService.running) command(MonitorService.CANCEL_SIMULATION)
                else { app.simulation.finish("本地测试已取消。"); app.notifyUi() }
            }
            else showInfo("没有进行中的模拟测试", "可以点击“立即模拟发布签到”开始一次新的本地测试。")
        })
        simulation.addView(label("需要通知使用权；两个 10 秒测试还需要闹钟权限。模拟提醒最多 30 秒，可提前停止。", 12, muted))
        trace = label("", 12, muted).apply { setTextIsSelectable(true); visibility = View.GONE }
        simulation.addView(button("查看 / 收起诊断记录") { trace.visibility = if (trace.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        simulation.addView(trace)
        simulation.addView(button("复制提醒诊断记录") { copyReminderTrace() })

        val strategy = compactSection(settingsBody,"提醒方式","语音、直接动态、通知与备用检查")
        strategy.addView(label("提醒偏好", 19, ink, true))
        mode = label("", 13, muted)
        strategy.addView(mode)
        backendPush=CheckBox(this).apply {
            text="直接获取签到动态（测试中）"
            ModernUi.toggle(this)
            setOnCheckedChangeListener { _,checked -> if(!rendering) {
                if(checked && (!app.prefs.getBoolean("loggedIn",false) || app.selectedCourses().isEmpty())) {
                    toast("请先在课程页登录并选择监测课程。");renderState()
                } else {
                    pauseForAction("修改直接提醒设置")
                    app.prefs.edit().putBoolean("backendPush",checked).apply()
                    toast("已保存，请回首页点“开始监测”。");renderState()
                }
            } }
        };strategy.addView(backendPush)
        strategy.addView(label("不用等学习通发手机通知。收到签到或课堂活动后，核对你选的课程，再通知、振动和播报。开启后回首页重新开始监测。",12,muted))
        strategy.addView(label("测试说明：还没用老师新发布的活动验证。原有通知提醒和定时检查可以同时保留。",12,muted))
        backendStatus=label("直接获取签到动态未开启",13,teal);strategy.addView(backendStatus)
        classroom = CheckBox(this).apply {
            text = "提醒课堂活动（随堂练习 / 测验）"; textSize = 13f; setTextColor(ink)
            isChecked = app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    pauseForAction("修改课堂活动提醒")
                    app.prefs.edit().putBoolean("classroomEnabled", checked).apply()
                    toast(if (checked) "已开启课堂活动提醒，请重新开始监测" else "已关闭课堂活动提醒，签到功能保留；请重新开始监测")
                }
            }
        }
        strategy.addView(classroom)
        strategy.addView(label("随堂练习和课堂测验使用“课堂活动”语音。", 12, muted))
        val notificationAccess = button("开启学习通通知使用权") {
            openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        verify = CheckBox(this).apply {
            text = "收到通知后，用账号接口补充确认"; textSize = 13f; setTextColor(ink)
            isChecked = app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    app.prefs.edit().putBoolean("verifyOnNotification", checked).apply()
                    toast(if (checked) "已开启：收到通知后补充查询" else "已关闭接口确认，仅按通知提醒")
                }
            }
        }
        strategy.addView(verify)
        strategy.addView(label("收到通知后确认所选课程，需先登录。", 12, muted))
        polling = CheckBox(this).apply {
            text = "启用备用轮询（默认关闭）"; textSize = 13f; setTextColor(ink)
            isChecked = app.prefs.getBoolean("fallbackPolling", NotificationRules.DEFAULT_POLLING)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    pauseForAction("修改备用轮询设置")
                    app.prefs.edit().putBoolean("fallbackPolling", checked).apply()
                    toast(if (checked) "已开启备用轮询；登录并选择课程后重新开始监测" else "已关闭备用轮询，请重新开始通知监测")
                    if (::interval.isInitialized) renderState()
                }
            }
        }
        strategy.addView(polling)
        strategy.addView(label("可能增加耗电，仅在通知不及时的时候开启。", 12, muted))
        voice = CheckBox(this).apply {
            text = "语音提醒：每 5 秒播放“签到”或“课堂活动”"; textSize = 13f; setTextColor(ink)
            isChecked = app.prefs.getBoolean("voiceEnabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    app.prefs.edit().putBoolean("voiceEnabled", checked).apply()
                    MonitorService.refreshAudioSettings()
                    toast(if (checked) "已开启离线语音提醒，使用手机闹钟音量" else "已关闭语音提醒，保留振动")
                }
            }
        }
        strategy.addView(voice)
        ring = CheckBox(this).apply {
            text = "循环响铃（使用闹钟音量）"; textSize = 13f; setTextColor(ink)
            isChecked = app.prefs.getBoolean("ringEnabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    app.prefs.edit().putBoolean("ringEnabled", checked).apply()
                    MonitorService.refreshAudioSettings()
                    toast(if (checked) "已开启循环铃声" else "已关闭循环铃声")
                }
            }
        }
        strategy.addView(ring)
        strategy.addView(label("使用闹钟音量，每 5 秒播报一次。", 12, muted))

        accountBody.removeAllViews()
        accountSwitcher=AccountSwitcherPanel(this,::switchAccount,::manageAccount)
        accountBody.addView(accountSwitcher)
        val auth = card(accountBody);loginCard=auth
        authStatus = label("登录后会保存到上方账号列表。", 13, teal)
        authStatus.setTextIsSelectable(true)
        auth.addView(authStatus)
        authProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
        }
        auth.addView(authProgress)
        authForm = column()
        auth.addView(authForm)
        authForm.addView(label("登录学习通",20,ink,true))
        account = field("手机号 / 超星账号", false)
        password = field("学习通密码", true)
        authForm.addView(account)
        authForm.addView(password)
        remember = CheckBox(this).apply { text = "保存账号和密码"; ModernUi.selection(this); textSize = 13f; setTextColor(muted) }
        authForm.addView(remember)
        authForm.addView(label("勾选后在本机加密保存，重新登录时自动填入；不勾选只保留登录状态。",12,muted))
        val profile = diagnosticRunCatching { JSONObject(app.vault.read("profile") ?: "{}") }.getOrDefault(JSONObject())
        account.setText(profile.optString("account"))
        remember.isChecked = profile.optBoolean("remember", false)
        if (remember.isChecked) password.setText(profile.optString("password"))
        remember.setOnCheckedChangeListener { _, checked -> DiagnosticLog.event("Credentials", "login_choice save=$checked applied_on_success=true") }
        login = button("登录并读取课程", true) { signIn() }
        authForm.addView(login)
        web = button("使用官方网页登录") {
            cancelBackground(false)
            pauseForAction("打开官方登录页")
            authStatus.text = "正在打开官方登录页…"
            toast("正在打开官方登录页")
            password.text.clear()
            startActivity(Intent(this, WebLoginActivity::class.java))
        }
        authForm.addView(web)
        cancelOperation = button("取消当前登录 / 查询") { cancelBackground(true) }.apply { visibility = View.GONE }
        auth.addView(cancelOperation)
        authForm.addView(label("遇到验证码或学校统一认证时，可在官方登录页完成。登录信息只保存在本机。", 12, muted))
        authForm.addView(label("学号登录请在官方页面选择学校 / 机构。切换后，课程选择和标签会随账号恢复。",12,muted))
        authFormToggle=accountSwitcher.addButton
        authFormToggle.setLoggedClickListener {
            authFormExpanded=!authFormExpanded
            if(authFormExpanded){account.text.clear();password.text.clear();remember.isChecked=false}
            renderState()
        }
        logout = button("退出当前账号") { signOut() }.apply{ModernUi.accountButton(this,true);textSize=13f}
        accountBody.addView(logout)
        val config = card(monitorBody)
        config.addView(label("监测课程", 19, ink, true))
        courseLabel = label("登录后选择课程", 13, muted)
        config.addView(courseLabel)
        select = button("选择课程") { chooseCourses() }
        refresh = button("刷新课程列表") { reloadCourses() }
        config.addView(select); config.addView(refresh)
        config.addView(button("按标签选择课程") { withCourseDialogs { chooseByTags() } })
        config.addView(button("管理课程标签") { withCourseDialogs { manageTags() } })
        config.addView(label("监测课程和标签按账号保存在本机，退出后重新登录同一账号会恢复。在“选择课程”中可保存并打标签，再按标签一次选中一组课程。", 12, muted))
        config.addView(label("备用轮询的每轮间隔（关闭轮询时不生效）", 13, muted))
        interval = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("30 秒", "60 秒", "120 秒"))
            setSelection(listOf(30, 60, 120).indexOf(app.prefs.getInt("interval", 60)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!rendering) {
                        val value = listOf(30, 60, 120)[position]
                        if (value != app.prefs.getInt("interval", 60)) {
                            pauseForAction("修改轮询间隔")
                            app.prefs.edit().putInt("interval", value).apply()
                            toast("轮询间隔已保存，重新开始监测后生效")
                        }
                    }
                }
            }
        }
        config.addView(interval)
        config.addView(label("优先勾选本学期、当天上课的课程。课程越多，一轮检查所需时间越长。网络异常时会自动降低查询频率。", 12, muted))
        accountBody.addView(button("账号绑定与代签") {startActivity(Intent(this,BoundAccountsActivity::class.java))}.apply{ModernUi.accountButton(this);textSize=13f})
        signingPanel=SigningPanel(this,app,{ renderState() })
        signingBody.addView(signingPanel,LinearLayout.LayoutParams(-1,-2))

        val permissions = compactSection(settingsBody,"通知权限","通知监测所需", true)
        val permissionCard = permissions.parent as View
        settingsBody.removeView(permissionCard)
        settingsBody.addView(permissionCard, 2)
        permissions.addView(label("通知使用权用于识别学习通发来的通知。允许 ClassBell 通知用于显示签到提醒和监测状态。", 13, ink))
        notificationHealth = label("", 13, muted).apply {
            background = ModernUi.shape(this@MainActivity, ModernUi.background, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        permissions.addView(notificationHealth)
        permissions.addView(notificationAccess)
        permissions.addView(button("允许 ClassBell 通知") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            else openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        val setup = compactSection(settingsBody,"锁屏与后台权限","按需设置全屏提醒、闹钟、电池优化和自启动")
        setup.addView(label("锁屏全屏提醒需要全屏权限和紧急通知分类。10 秒测试需要闹钟权限。后台经常断开时，再检查电池优化及自启动。", 13, muted))
        health = label("", 13, muted).apply {
            background = ModernUi.shape(this@MainActivity, ModernUi.background, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        setup.addView(health)
        backgroundTitle = label("手机后台设置", 15, ink, true)
        setup.addView(backgroundTitle)
        setup.addView(label("手机类型（可手动选择）", 13, ink))
        phoneType = Spinner(this).apply {
            contentDescription = "选择手机类型"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BackgroundSettings.choiceLabels())
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
            setSelection(BackgroundSettings.choices.indexOf(BackgroundSettings.selection(app.prefs)))
        }
        setup.addView(phoneType)
        setup.addView(label("选择后立即切换下方指引并自动保存，监测继续。", 12, muted))
        backgroundSummary = label("", 12, muted)
        setup.addView(backgroundSummary)
        setup.addView(button("设置电池优化例外") {
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                toast("电池优化例外已开启，无需重复设置")
            else openSetting(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        setup.addView(button("设置自启动和后台运行") { showBackgroundGuide() })
        xiaomiPermissions = button("小米锁屏显示和后台弹出设置") {
            ModernUi.dialog(this).setTitle("小米其他权限")
                .setMessage("在本 App 的其他权限中，如有“锁屏显示”“后台弹出界面”，请手动允许。再到通知管理中允许悬浮通知和锁屏通知。Android 的全屏提醒权限仍需通过下方入口单独检查。\n\n入口不可用时会打开应用设置，请查找“其他权限”；各系统版本菜单可能不同。")
                .setPositiveButton("打开其他权限") { _, _ -> openVendorSetting(BackgroundSettings.xiaomiPermissionsIntent(packageName)) }
                .setNegativeButton("稍后设置", null).show()
        }
        setup.addView(xiaomiPermissions)
        setup.addView(button("允许闹钟和提醒（10 秒测试必需）") {
            if (Build.VERSION.SDK_INT >= 31) openSetting(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else showInfo("已支持系统闹钟", "当前系统无需额外授权，可直接开始 10 秒测试。")
        })
        setup.addView(button("允许锁屏全屏提醒") {
            if (Build.VERSION.SDK_INT >= 34) openSetting(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            else showInfo("检查紧急通知分类", "当前系统无需单独授权全屏通知；请允许紧急提醒分类的横幅和锁屏显示。")
        })
        setup.addView(button("设置签到紧急通知分类") {
            UrgentAlert.ensureChannel(this)
            openSetting(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, UrgentAlert.CHANNEL))
        })
        setup.addView(button("调整闹钟音量") { openSetting(Intent(Settings.ACTION_SOUND_SETTINGS)) })
        setup.addView(label("锁屏弹出需要允许全屏提醒，以及“签到紧急提醒”分类的横幅 / 锁屏显示。铃声和语音使用闹钟音量。勿扰、系统强停、系统清理及学习通未送达通知仍可能影响提醒，必须在你的手机上实测。", 12, muted))
        setup.addView(button("打开应用设置") {
            openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        setup.addView(label("厂商自启动、省电策略和锁屏显示权限需要在系统设置中手动核对。请实测锁屏、振动模式和勿扰模式下的表现。", 12, muted))
        body.addView(label("上课前开启提醒，结束后停止。", 12, muted))
        settingsBody.addView(label("Class Bell ${BuildConfig.VERSION_NAME}\n作者：Forest5326", 12, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        listOf(classroom, verify, polling, voice, ring).forEach(ModernUi::toggle)
        listOf(interval, phoneType).forEach(ModernUi::spinner)
        val bottom = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(6), dp(10), dp(6)); elevation = dp(3).toFloat()
        }
        listOf("首页", "课程", "作业", "我的").forEachIndexed { index, text ->
            val item = button(text) { showPage(primaryPages[index],false) }
            item.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
            item.minHeight = dp(66); item.textSize = 12f; item.compoundDrawablePadding = dp(5)
            navigation.add(item); bottom.addView(item)
        }
        shell.addView(bottom)
        phoneType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = BackgroundSettings.choices[position]
                if (selected != BackgroundSettings.selection(app.prefs)) {
                    BackgroundSettings.select(app.prefs, selected)
                    renderState()
                }
            }
        }
        renderState()
        val savedRoute = app.prefs.getString("ui.route", null)
        val savedPage = if (savedRoute != null) routes.indexOf(savedRoute).coerceAtLeast(0)
            else listOf(0, 1, 3, 4)[app.prefs.getInt("ui.page", 0).coerceIn(0, 3)]
        showPage(savedPage,false)
        if(!app.prefs.getBoolean("ui.helpPromptShown",false)) {
            val message=label("请查看使用帮助",20,ink,true).apply{gravity=Gravity.CENTER;setPadding(dp(24),dp(28),dp(24),dp(20))}
            helpPrompt=ModernUi.dialog(this).setView(message).setPositiveButton("知道了",null).create().also{it.show()}
            app.prefs.edit().putBoolean("ui.helpPromptShown",true).apply()
        }
    }

    override fun onStart() {
        super.onStart()
        app.addUiListener(listener)
    }
    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            if (!busy) app.prefs.getString("lastAuthMessage", null)?.let { authStatus.text = it }
            renderState()
            homeworkPanel.setPageVisible(currentPage == 2)
            ListenerRecovery.ensure(this)
            if (!busy) MonitorRecovery.restore(this, "返回应用")
        }
    }
    override fun onStop() {
        accountActions?.dismiss();accountActions=null
        homeworkPanel.setPageVisible(false)
        app.removeUiListener(listener)
        super.onStop()
    }
    override fun onDestroy() { helpPrompt?.dismiss();signingPanel.close(); homeworkPanel.close(); cancelBackground(false); worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Uses the platform document picker on all supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==SigningPanel.PRESET_REQUEST) signingPanel.onPresetResult(if(resultCode==RESULT_OK) data?.data else null)
    }

    private fun signIn() {
        val user = account.text.toString().trim()
        val secret = password.text.toString()
        val save = remember.isChecked
        if (busy) { showInfo("正在处理", "当前登录或查询尚未完成。可点击“取消当前登录 / 查询”，或直接打开官方网页登录。"); return }
        if (user.isBlank() || secret.isBlank()) {
            authStatus.text = "请先填写学习通账号和密码，再点击登录。"
            showInfo("还没有填写完整", "请填写学习通账号和密码。登录不需要通知权限。")
            return
        }
        try {app.rememberCurrentAccount()}catch (diagnosticError: Exception) { DiagnosticLog.error("MainActivity", diagnosticError);showInfo("当前账号保存未完成",if(diagnosticError is ApiProblem)diagnosticError.message.orEmpty() else "请先检查已保存账号，原数据已保留。");return}
        app.prefs.edit().putBoolean("loggedIn", false).apply()
        background("正在登录并读取课程，请稍候…") { token ->
            val courses = app.api.login(user, secret)
            commitOperation(token) { app.saveSession(courses, user, secret, save) }
            "登录成功，读取到 ${courses.size} 个课程/班级。课程选择和标签已按账号载入，请核对后重新开始监测。"
        }
        password.text.clear()
    }
    private fun reloadCourses() {
        if (!app.prefs.getBoolean("loggedIn", false)) { showInfo("请先登录", "先用账号密码登录，或点“使用官方网页登录”，完成后再读取课程。"); return }
        background("正在刷新课程…") { token ->
            val courses = app.api.courses()
            val saved = JSONObject(app.vault.read("profile") ?: "{}")
            commitOperation(token) { app.saveSession(courses, saved.optString("account"), saved.optString("password"), saved.optBoolean("remember"), updateCredentials=false) }
            "课程列表已刷新，共 ${courses.size} 个课程/班级。"
        }
    }
    private fun background(message: String, block: (Int) -> String) {
        if (busy) { showInfo("正在处理", "请等待当前操作结束，或点“取消当前登录 / 查询”。"); return }
        pauseForAction("登录或读取课程")
        val token = synchronized(operationGuard) { ++operationGeneration }
        busy = true
        authStatus.text = message
        authProgress.visibility = View.VISIBLE
        cancelOperation.visibility = View.VISIBLE
        app.setStatus(message); toast(message); renderState()
        operationTask = worker.submit {
            var failed = false
            val result = try { DiagnosticLog.operation("AccountTask",message) { block(token) } }
            catch (diagnosticError: LoginRequired) { DiagnosticLog.error("MainActivity", diagnosticError);
                commitOperation(token) { app.prefs.edit().putBoolean("loggedIn", false).apply() }
                failed = true; "登录会话未能完成，请使用官方网页登录。登录不依赖通知权限。"
            } catch (diagnosticError: java.util.concurrent.CancellationException) { DiagnosticLog.error("MainActivity", diagnosticError); return@submit }
            catch (e: Exception) { DiagnosticLog.error("MainActivity", e); failed = true; FailureMessages.describe(e) }
            runOnUiThread {
                if (isDestroyed || token != operationGeneration) return@runOnUiThread
                busy = false
                authStatus.text = result
                app.prefs.edit().putString("lastAuthMessage", result).apply()
                app.setStatus(result)
                renderState()
                showInfo(if (failed) "操作未完成" else "操作完成", result)
            }
        }
    }
    private fun commitOperation(token: Int, action: () -> Unit) {
        synchronized(operationGuard) {
            if (token != operationGeneration || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            action()
        }
    }
    private fun cancelBackground(showFeedback: Boolean) {
        val wasBusy = busy
        synchronized(operationGuard) { operationGeneration++ }
        if (wasBusy) { app.api.cancelRequests(); operationTask?.cancel(true) }
        busy = false
        if (::authProgress.isInitialized) {
            authProgress.visibility = View.GONE
            cancelOperation.visibility = View.GONE
        }
        if (showFeedback) {
            authStatus.text = if (wasBusy) "已取消当前操作，可以重新登录或打开官方网页登录。" else "当前没有进行中的登录或查询。"
            toast(authStatus.text.toString())
            renderState()
        }
    }
    private fun chooseCourses() {
        if (busy) { showInfo("正在处理", "请先等待或取消当前登录 / 查询。"); return }
        val courses = app.courses()
        if (courses.isEmpty()) { showInfo("还没有课程", "请先登录并读取课程，再选择需要监测的课程。"); return }
        courseDialogs().chooseCourses()
    }
    private fun courseDialogs() = CourseDialogs(this, app, { pauseForAction("修改监测课程") }, { renderState() })
    private fun withCourseDialogs(action: CourseDialogs.() -> Unit) {
        if (busy) { showInfo("正在处理", "请先等待或取消当前登录 / 查询。"); return }
        if (app.courses().isEmpty() || app.prefs.getString("identity", "").isNullOrBlank()) {
            showInfo("还没有课程", "请先登录并读取课程，课程选择和标签会按账号恢复。"); return
        }
        courseDialogs().action()
    }
    private fun signOut() {
        background("正在清除本机登录信息…") { token ->
            commitOperation(token) {
                app.clearSession()
            }
            runOnUiThread {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                account.text.clear(); password.text.clear(); remember.isChecked = false
            }
            "已退出，登录信息已清除；监测课程和标签已保留，同一账号重新登录会恢复。"
        }
    }
    private fun switchAccount(account:BoundAccount) {
        if(busy){toast("请先等待或取消当前操作。");return}
        try{app.rememberCurrentAccount()}catch (diagnosticError: Exception) { DiagnosticLog.error("MainActivity", diagnosticError);toast("当前登录保存未完成，请先检查已保存账号。");return}
        app.prefs.edit().putBoolean("loggedIn",false).apply()
        background("正在核对并切换账号…"){token->
            val jar=SessionCookies(app.vault,null).apply{replaceWith(account.cookies)}
            val candidate=ChaoxingApi(jar)
            try {
                val courses=candidate.courses()
                if(accountKey(candidate.verifiedProfile?.uid.orEmpty())!=account.id)throw LoginRequired()
                commitOperation(token){
                    if(!accountShelf.current(account))throw java.util.concurrent.CancellationException()
                    app.cookies.replaceWith(jar.snapshot())
                    app.saveSession(courses,account.label,"",false,candidate.verifiedProfile,updateCredentials=false)
                }
                "账号已切换，课程选择与照片按账号载入。请重新开始监测。"
            }finally{candidate.cancelRequests()}
        }
    }
    private fun renderAccounts() {
        accountSwitcher.render(accountShelf.list(),app.prefs.getString("identity","").orEmpty(),app.prefs.getBoolean("loggedIn",false),app.displayName(),app.courses().size,app.selectedCourses().size,busy)
    }
    private fun manageAccount(value:BoundAccount?) {
        accountActions?.dismiss()
        val current=value==null || (value.id==app.prefs.getString("identity","") && app.prefs.getBoolean("loggedIn",false))
        val owner=app.prefs.getString("identity","");val revision=app.signSessionGeneration
        fun stillCurrent():Boolean {
            val valid=!isFinishing && !isDestroyed && !busy && owner==app.prefs.getString("identity","") && revision==app.signSessionGeneration && (value==null || accountShelf.current(value))
            if(!valid)toast("账号状态已变化，请重新打开账号操作。")
            return valid
        }
        val actions=mutableListOf(AccountActionSheet.Action(if(current)"重新登录" else "切换到此账号",
            if(current)"重新填写登录信息" else "载入此账号的课程与提醒选择",if(current)"refresh" else "profile") {
                if(stillCurrent()) {
                if(!current)switchAccount(value!!)
                else {authFormExpanded=true;val saved=app.savedCredentials.read(owner.orEmpty());account.setText(saved?.account.orEmpty());password.setText(saved?.password.orEmpty());remember.isChecked=saved!=null;renderState()}
                }
            })
        val credentialOwner=value?.id ?: owner.orEmpty()
        if(app.savedCredentials.read(credentialOwner)!=null)actions+=AccountActionSheet.Action("不再保存账号和密码","清除本机密码，保留当前登录状态","remove",true) {
            if(stillCurrent()){app.forgetCredentials(credentialOwner);if(current){account.text.clear();password.text.clear();remember.isChecked=false};toast("已清除保存的账号和密码。");renderState()}
        }
        if(value!=null)actions+=AccountActionSheet.Action("移除保存的登录","保留课程选择和标签","remove",true) {
            if(stillCurrent())ModernUi.dialog(this).setTitle("移除保存的登录？").setMessage("只删除此设备上的快捷登录，课程选择和标签仍会保留。")
                .setNegativeButton("取消",null).setPositiveButton("移除"){_,_->if(stillCurrent()){cancelBackground(false);app.forgetCredentials(value.id);accountShelf.unbind(value.id);app.prefs.edit().putBoolean("account.remember.${value.id}",false).apply();renderState()}}.show()
        }
        accountActions=AccountActionSheet.show(this,value?.label ?: app.displayName().ifBlank{"当前账号"},if(current)"正在使用的账号" else "已保存在此设备",actions)
    }
    private fun startMonitoring() {
        if (MonitorService.running && MonitorService.simulationOnly) {
            showInfo("本地模拟测试中", "请先结束或取消模拟测试，再开始正式通知监测。测试最多提醒 30 秒。")
            return
        }
        if (MonitorService.running) { ListenerRecovery.ensure(this); showInfo("监测正在运行", if(app.prefs.getBoolean("backendPush",false))
            "正在等待课程动态。你可以回桌面或锁屏，老师发布新活动后再查看接收与验证记录。" else
            "当前在等待学习通签到或课堂活动通知。已授权但断开的通知连接会自动重连，无需反复开关通知使用权。"); return }
        if (busy) { showInfo("正在处理", "请先等待或取消当前登录 / 查询，再开始监测。"); return }
        val fallback = app.prefs.getBoolean("fallbackPolling", NotificationRules.DEFAULT_POLLING)
        val backendAvailable=app.prefs.getBoolean("backendPush",false) && app.prefs.getBoolean("loggedIn",false) && app.selectedCourses().isNotEmpty()
        if (fallback && (!app.prefs.getBoolean("loggedIn", false) || app.selectedCourses().isEmpty())) {
            toast("备用轮询需要先登录并选择课程；也可关闭轮询，仅按通知提醒")
            return
        }
        if (!XxtNotificationListener.hasAccess(this) && !fallback && !backendAvailable) {
            toast("请先允许“学习通签到通知增强”的通知使用权")
            openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        requestNotificationsThen(MonitorService.START)
    }
    private fun simulateNotification(delayMs: Long, kind: ReminderKind = ReminderKind.SIGN_IN) {
        if (busy) { showInfo("正在处理", "请先等待或取消当前登录 / 查询，再开始本地测试。"); return }
        if (kind == ReminderKind.CLASSROOM && !app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)) {
            showInfo("课堂活动提醒已关闭", "先勾选“提醒课堂活动”，再进行课堂活动通知模拟。")
            return
        }
        if (delayMs > 0 && !requireExactAlarm()) return
        if (!XxtNotificationListener.hasAccess(this)) {
            showInfo("先开启通知使用权", "完整模拟需要经过系统通知监听。请在接下来的系统页面允许“学习通签到通知增强”，返回后再点模拟按钮。")
            openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        simulationDelayMs = delayMs
        simulationKind = kind
        requestNotificationsThen(MonitorService.SIMULATE)
    }
    private fun requireExactAlarm(): Boolean {
        if (AlarmScheduler.allowed(this)) return true
        ModernUi.dialog(this).setTitle("需要闹钟和提醒权限")
            .setMessage("息屏后的 10 秒测试由系统闹钟唤醒。请允许本 App 设置闹钟和提醒，返回后再点测试；未授权不会开始倒计时。")
            .setPositiveButton("去授权") { _, _ ->
                if (Build.VERSION.SDK_INT >= 31) openSetting(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }.setNegativeButton("取消", null).show()
        return false
    }
    private var permissionAction: String? = null
    private fun requestNotificationsThen(action: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionAction = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        } else launchService(action)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DiagnosticLog.event("Permissions", "request=$requestCode granted=${grantResults.count{it==PackageManager.PERMISSION_GRANTED}} total=${grantResults.size}")
        if (requestCode == 9) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) permissionAction?.let { launchService(it) }
            else toast("请允许通知，以便随时停止振动和监测")
            permissionAction = null
        }
        renderState()
    }
    private fun launchService(action: String) {
        try {
            val request = Intent(this, MonitorService::class.java).setAction(action)
            if (action == MonitorService.SIMULATE) request.putExtra(MonitorService.EXTRA_SIMULATION_DELAY, simulationDelayMs)
                .putExtra(MonitorService.EXTRA_SIMULATION_KIND, simulationKind.name)
            startForegroundService(request)
            if (action == MonitorService.START) offerBackgroundSetup()
            if (action == MonitorService.SIMULATE) toast("本地模拟测试已启动，请查看四步进度")
        }
        catch (diagnosticError: Exception) { DiagnosticLog.error("MainActivity", diagnosticError); app.setStatus("系统未允许启动监测，请检查后台运行设置") }
    }
    private fun command(action: String) {
        if (MonitorService.running || app.prefs.getString("alert", "").orEmpty().isNotEmpty()) {
            startService(Intent(this, MonitorService::class.java).setAction(action))
        }
    }
    private fun renderState() {
        homeworkPanel.syncSession()
        signingPanel.render()
        courseListPanel.render()
        renderAccounts()
        profilePanel.render()
        realSignStatus.text = RealSignDetection.description(this)
        realClassTaskStatus.text = RealClassTaskDetection.description(this)
        val text = app.prefs.getString("alert", "").orEmpty()
        alert.text = text; alert.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        acknowledgeButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        simulationStatus.text = app.simulation.snapshot()?.description() ?: "尚未开始本地模拟测试。"
        val running = MonitorService.running
        val backendConnected=running && !MonitorService.simulationOnly && app.prefs.getBoolean("loggedIn",false) &&
            app.prefs.getBoolean("backend.connected",false) && app.prefs.getString("backend.owner","")==app.prefs.getString("identity","")
        val restoring = !running && app.prefs.getBoolean("monitorEnabled", false)
        monitorBadge.text = if (running) "监测中" else if (restoring) "待恢复" else "未开启"
        status.text = if (running) "正在守候课堂动态" else if (restoring) "监测等待恢复" else "开启课堂提醒"
        homeHealth.text = when {
            backendConnected -> "消息服务已连上，点此查看验证进度"
            app.prefs.getBoolean("backendPush",false) -> "直接提醒尚未连接，点此查看进度"
            !XxtNotificationListener.hasAccess(this) -> "通知使用权未开启，点此检查设置"
            !XxtNotificationListener.connected -> "通知监听暂未连接，点此检查设置"
            else -> "通知连接正常，点此检查后台设置"
        }
        rendering = true
        val loggedIn = app.prefs.getBoolean("loggedIn", false)
        if (loggedIn && !lastLoginState) authFormExpanded = false
        lastLoginState = loggedIn
        val previousIdentity = !app.prefs.getString("identity", "").isNullOrBlank()
        authForm.visibility = if ((!loggedIn || authFormExpanded) && !busy) View.VISIBLE else View.GONE
        authFormToggle.visibility = if (loggedIn) View.VISIBLE else View.GONE
        authFormToggle.text = if (authFormExpanded) "收起登录" else "添加账号"
        loginCard.visibility=if(!loggedIn || authFormExpanded || busy)View.VISIBLE else View.GONE
        authStatus.visibility = if (loggedIn && !busy && !authFormExpanded) View.GONE else View.VISIBLE
        if (!loggedIn && previousIdentity && !busy && (authStatus.text.startsWith("登录成功") || authStatus.text.startsWith("网页登录验证成功") || authStatus.text.startsWith("课程列表已刷新")))
            authStatus.text = "登录会话当前无效，请重新登录；课程选择和标签仍保留。"
        logout.visibility = if (loggedIn || previousIdentity) View.VISIBLE else View.GONE
        val family = phoneFamily
        backgroundTitle.text = BackgroundSettings.title(family)
        backgroundSummary.text = BackgroundSettings.summary(family)
        xiaomiPermissions.visibility = if (family == PhoneFamily.XIAOMI) View.VISIBLE else View.GONE
        val phoneIndex = BackgroundSettings.choices.indexOf(BackgroundSettings.selection(app.prefs))
        if (phoneType.selectedItemPosition != phoneIndex) phoneType.setSelection(phoneIndex)
        account.isEnabled = !busy; password.isEnabled = !busy; remember.isEnabled = !busy
        login.isEnabled = true; web.isEnabled = true; logout.isEnabled = true
        login.text = if (busy) "正在连接学习通…" else "登录并读取课程"
        authProgress.visibility = if (busy) View.VISIBLE else View.GONE
        cancelOperation.visibility = if (busy) View.VISIBLE else View.GONE
        refresh.isEnabled = true
        select.isEnabled = true
        verify.isEnabled = true; polling.isEnabled = true; voice.isEnabled = true; ring.isEnabled = true; classroom.isEnabled = true
        verify.isChecked = app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY)
        polling.isChecked = app.prefs.getBoolean("fallbackPolling", NotificationRules.DEFAULT_POLLING)
        backendPush.isChecked=app.prefs.getBoolean("backendPush",false)
        val verifiedAt=if(app.prefs.getString("backend.verifiedOwner",null)==app.prefs.getString("identity",null))app.prefs.getLong("backend.verifiedAt",0) else 0
        backendStatus.text=(if(backendPush.isChecked) when {
            !loggedIn || app.selectedCourses().isEmpty() -> "请先登录并选择课程"
            !running || MonitorService.simulationOnly -> "回首页点“开始监测”后连接"
            else -> app.prefs.getString("backend.status","正在连接学习通消息服务…")
        } else "直接获取签到动态未开启")+
            if(verifiedAt>0) "\n已收到并核对：${AlertTrace.time(verifiedAt)} · ${app.prefs.getString("backend.verifiedKind","")} · ${app.prefs.getString("backend.verifiedCourse","")}" else "\n真实签到 / 课堂活动提醒：尚未验证"
        voice.isChecked = app.prefs.getBoolean("voiceEnabled", true)
        ring.isChecked = app.prefs.getBoolean("ringEnabled", true)
        classroom.isChecked = app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)
        interval.isEnabled = true
        start.isEnabled = true
        start.text = if (running) "监测中 · 点此查看状态" else if(backendPush.isChecked) "开始监测" else "开始通知监测"
        monitorTitle.text=if(backendPush.isChecked) "直接提醒 · 测试中" else "通知监测"
        monitorSummary.text=if(backendPush.isChecked) "直接等候课程动态，不用先等学习通发通知。真实新活动提醒仍待验证。" else "接收学习通通知，签到与课堂任务分别提醒。"
        mode.text = if(backendPush.isChecked) "直接获取动态已开启；定时检查${if(polling.isChecked) "已开" else "关闭"}。"
            else if (polling.isChecked) "通知触发优先，另有备用轮询。" else "等待通知时不查询接口，备用轮询已关闭。"
        courseLabel.text = getString(R.string.course_count, app.courses().size, app.selectedCourses().size)
        val notification = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val listenerAllowed = XxtNotificationListener.hasAccess(this)
        val listenerStatus = when {
            !listenerAllowed -> "未授权"
            XxtNotificationListener.connected -> "已连接"
            else -> "断开，自动重连中"
        }
        notificationHealth.text = "通知使用权：$listenerStatus\nClassBell 通知：${if(notification) "已允许" else "未允许"}"
        health.text = "电池优化例外：${if(battery) "已设置" else "未设置"}" +
            "\n闹钟和提醒：${if (AlarmScheduler.allowed(this)) "已允许" else "未允许"}" +
            "　锁屏全屏提醒：${if (UrgentAlert.fullScreenAllowed(this)) "已允许" else "未允许"}" +
            "\n紧急通知分类：${when (getSystemService(NotificationManager::class.java).getNotificationChannel(UrgentAlert.CHANNEL)?.importance) {
                NotificationManager.IMPORTANCE_NONE -> "已关闭"
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "高优先级"
                null -> "尚未创建"
                else -> "已降低，请开启横幅"
            }}　闹钟音量：${(getSystemService(AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_ALARM)}"
        health.append("\n监测服务：${if (running) "运行中" else if (app.prefs.getBoolean("monitorEnabled", false)) "中断，等待恢复" else "未开启"}" +
            "　系统后台限制：${if (Build.VERSION.SDK_INT >= 28 && getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted) "已限制，请允许后台运行" else "未限制"}")
        if (family == PhoneFamily.XIAOMI)
            health.append("\n小米自启动 / 无限制 / 锁屏显示：请到系统设置手动核对")
        if (family == PhoneFamily.OPPO_ONEPLUS)
            health.append("\nOPPO / 一加自启动 / 后台耗电 / 锁屏通知：请到系统设置手动核对")
        val diagnostics = AlertTrace.description(this)
        receiptTrace.text = diagnostics
        trace.text = "提醒诊断（最近 20 步）\n$diagnostics"
        rendering = false
    }
    private fun copyReminderTrace() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("提醒诊断",
            "${notificationHealth.text}\n${health.text}\n${AlertTrace.description(this)}"))
        toast("诊断记录已复制")
    }
    private fun pauseForAction(action: String) {
        if (MonitorService.running) {
            haltMonitoring()
            app.api.cancelRequests()
            toast("已暂停监测，准备${action}；完成后请重新开始监测")
        }
    }
    private fun haltMonitoring() {
        MonitorService.stopMonitoring(this)
    }
    private fun repairListener() {
        if (!XxtNotificationListener.hasAccess(this)) {
            toast("通知使用权尚未授权，请允许一次")
            openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        ListenerRecovery.ensure(this)
        MonitorRecovery.restore(this, "手动修复连接")
        toast(if (XxtNotificationListener.connected) "通知连接正常，授权已保留" else "已请求重连，请稍候；无需关闭再开启通知使用权")
        renderState()
    }
    private fun offerBackgroundSetup() {
        val family = phoneFamily
        val shownKey = "backgroundSetupShown029_${family.name}"
        if (app.prefs.getBoolean(shownKey, false) ||
            (family !in setOf(PhoneFamily.XIAOMI, PhoneFamily.OPPO_ONEPLUS) &&
                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))) return
        app.prefs.edit().putBoolean(shownKey, true).apply()
        ModernUi.dialog(this).setTitle("允许持续后台提醒（一次设置）")
            .setMessage("监测已启动。" + BackgroundSettings.summary(family) + "\n\n点击查看本机设置步骤，完成后返回检查并测试。")
            .setPositiveButton("查看后台设置") { _, _ -> showBackgroundGuide() }
            .setNegativeButton("稍后设置", null).show()
    }
    private fun showBackgroundGuide() {
        val family = phoneFamily
        ModernUi.dialog(this).setTitle(BackgroundSettings.title(family))
            .setMessage(BackgroundSettings.guide(family))
            .setPositiveButton(if (family == PhoneFamily.XIAOMI) "打开自启动设置" else "打开应用设置") { _, _ ->
                if (family == PhoneFamily.XIAOMI) openVendorSetting(BackgroundSettings.xiaomiAutostartIntent())
                else openVendorSetting(BackgroundSettings.appDetailsIntent(packageName))
            }.setNegativeButton("知道了", null).show()
    }
    private fun openVendorSetting(intent: Intent) {
        if (!BackgroundSettings.openWithFallback(packageName, intent) { startActivity(it) })
            toast("请在系统设置中搜索自启动、其他权限或应用管理")
    }
    private fun showHelpMenu(anchor: View) {
        HelpUi.menu(this,
            {HelpUi.content(this,"使用指南",HelpContent.guide(phoneFamily),::showBackgroundGuide)},
            {HelpUi.content(this,"常见问题处理方法",HelpContent.troubleshooting,::showBackgroundGuide)},
            ::showReleaseNotes)
    }
    private fun showReleaseNotes() {
        HelpUi.content(this,"更新日志 · 当前 v${BuildConfig.VERSION_NAME}",ReleaseNotes.description())
    }
    private fun showInfo(title: String, message: String) {
        if (!isFinishing && !isDestroyed) ModernUi.dialog(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
    }
    private fun openSetting(intent: Intent) { try { startActivity(intent) } catch (diagnosticError: Exception) { DiagnosticLog.error("MainActivity", diagnosticError); toast("请到手机系统设置中手动开启") } }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun page(host: FrameLayout, title: String, subtitle: String, headline: String = title,
                     fillViewport:Boolean=false): LinearLayout {
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            contentDescription = "${title}页面"
            isFillViewport = fillViewport
        }
        val body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(24)) }
        body.addView(label(headline, if (headline != title) 30 else 22, ink, true).apply{tag="page-heading"})
        if (subtitle.isNotBlank()) {
            body.addView(label(subtitle, 13, muted))
        }
        scroll.addView(body, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(640)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        pages.add(scroll)
        return body
    }
    private fun showPage(index: Int,remember:Boolean=true,clearHistory:Boolean=!remember) {
        if(index !in pages.indices)return
        if(clearHistory)history.clear() else if(remember && currentPage!=index)history.addLast(currentPage)
        currentPage = index
        back.visibility=if(index in primaryPages)View.GONE else View.VISIBLE
        credits.visibility=if(index==0)View.VISIBLE else View.GONE
        pageTitle.text=listOf("Class Bell","课程","作业","测试与诊断","提醒设置","我的","切换账号","签到中心","提醒课程","提醒记录","更多工具")[index]
        pageTitle.textSize=if(index==0)24f else 21f
        pages.forEachIndexed{at,page->page.findViewWithTag<View>("page-heading")?.visibility=if(at==0)View.VISIBLE else View.GONE}
        pages.forEachIndexed { at, page -> page.visibility = if (at == index) View.VISIBLE else View.GONE }
        homeworkPanel.setPageVisible(index == 2)
        val icons = listOf("home","courses","homework","profile")
        navigation.forEachIndexed { at, item ->
            val selected = primaryPages[at] == index || (at==3 && index !in primaryPages)
            ModernUi.button(item, textOnly = true)
            item.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22146B53),
                ModernUi.shape(this, if (selected) ModernUi.soft else Color.WHITE, 18), ModernUi.shape(this, Color.WHITE, 18))
            item.setTextColor(if (selected) teal else muted)
            item.textSize = 12f; item.minHeight = dp(66); item.compoundDrawablePadding = dp(5)
            item.setPadding(dp(4), dp(8), dp(4), dp(8)); item.isSelected = selected
            item.setCompoundDrawablesRelativeWithIntrinsicBounds(null, ModernUi.icon(this, icons[at], if (selected) teal else muted), null, null)
            item.contentDescription = "${item.text}，${if (selected) "已选择" else "打开页面"}"
        }
        if (app.prefs.getString("ui.route", null) != routes[index]) app.prefs.edit().putString("ui.route", routes[index]).apply()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if(history.isNotEmpty())showPage(history.removeLast(),false,false) else if(currentPage !in primaryPages)showPage(5,false) else if(currentPage!=0)showPage(0,false) else super.onBackPressed() }
    private fun compactSection(parent:LinearLayout,title:String,subtitle:String,open:Boolean=false):LinearLayout {
        val outer=card(parent);val content=column().apply{visibility=if(open)View.VISIBLE else View.GONE}
        outer.addView(button("$title　⌄"){content.visibility=if(content.visibility==View.VISIBLE)View.GONE else View.VISIBLE})
        outer.addView(label(subtitle,12,muted));outer.addView(content);return content
    }
    private fun profileAction(title:String,icon:String,action:()->Unit)=button("$title　›",action=action).apply {
        tag=title;text=title
        background=android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22146B53),ModernUi.shape(this@MainActivity,Color.WHITE,16),ModernUi.shape(this@MainActivity,Color.WHITE,16))
        gravity=Gravity.CENTER_VERTICAL or Gravity.START;minHeight=dp(54);setPadding(dp(16),dp(12),dp(16),dp(12));setTextColor(ink)
        setCompoundDrawablesRelativeWithIntrinsicBounds(ModernUi.icon(this@MainActivity,icon,teal),null,null,null);compoundDrawablePadding=dp(12)
    }
    private fun pair(parent: LinearLayout, first: Button, second: Button, firstWeight: Float = 1f) {
        val row = LinearLayout(this).apply {
            orientation = if (resources.configuration.fontScale > 1.25f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf(first, second).forEachIndexed { index, view ->
            view.layoutParams = if (row.orientation == LinearLayout.HORIZONTAL)
                LinearLayout.LayoutParams(0, -2, if (index == 0) firstWeight else 1f).apply { topMargin = dp(8); if (index == 1) marginStart = dp(8) }
            else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            row.addView(view)
        }
        parent.addView(row)
    }
    private fun receiptHeading(parent: LinearLayout, title: String, clearDescription: String, clear: () -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 18, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("清除", action = clear).apply {
            contentDescription = clearDescription; ModernUi.button(this, textOnly = true)
            textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(8))
        }, LinearLayout.LayoutParams(dp(48), -2))
        parent.addView(row)
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun card(parent: LinearLayout): LinearLayout = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = ModernUi.shape(this@MainActivity, Color.WHITE, 20)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(2) })
    }
    private fun field(hint: String, secret: Boolean) = EditText(this).apply {
        this.hint = hint; textSize = 16f; setSingleLine(true); isSaveEnabled = false
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setPadding(dp(10), dp(12), dp(10), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        ModernUi.input(this)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        ModernUi.button(this, primaryAction = primary)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setLoggedClickListener {
            try { action() } catch (diagnosticError: Exception) { DiagnosticLog.error("MainActivity", diagnosticError); showInfo("操作未完成", "这项操作未能完成，请重试；也可以截屏保留当前状态。") }
        }
    }
}
