package cn.classbell.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class HomeworkOpenResult { DIRECT, APP_HOME, UNAVAILABLE }

object HomeworkLinks {
    fun open(context: Context, url: String, launch: (Intent) -> Unit = { context.startActivity(it) },
             appLauncher: () -> Intent? = { context.packageManager.getLaunchIntentForPackage(NotificationRules.SOURCE_PACKAGE) }): HomeworkOpenResult {
        if (!HomeworkParser.safeUrl(url)) return HomeworkOpenResult.UNAVAILABLE
        try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(NotificationRules.SOURCE_PACKAGE))
            return HomeworkOpenResult.DIRECT
        } catch (diagnosticError: ActivityNotFoundException) { DiagnosticLog.error("HomeworkPanel", diagnosticError); /* Try the installed application's launcher. */ }
        catch (diagnosticError: SecurityException) { DiagnosticLog.error("HomeworkPanel", diagnosticError); /* This version may not export a matching activity. */ }
        return try {
            val intent = appLauncher() ?: return HomeworkOpenResult.UNAVAILABLE
            launch(intent)
            HomeworkOpenResult.APP_HOME
        } catch (diagnosticError: ActivityNotFoundException) { DiagnosticLog.error("HomeworkPanel", diagnosticError); HomeworkOpenResult.UNAVAILABLE }
        catch (diagnosticError: SecurityException) { DiagnosticLog.error("HomeworkPanel", diagnosticError); HomeworkOpenResult.UNAVAILABLE }
    }
}

class HomeworkPanel(
    private val activity: Activity,
    private val app: BellApp,
    private val goToLogin: () -> Unit,
    private val source: HomeworkSource = HomeworkApi(app.cookies),
    private val cache: HomeworkCache = EncryptedHomeworkCache(app.vault),
    private val openLink: (String) -> HomeworkOpenResult = { HomeworkLinks.open(activity, it) },
    private val fixedCourses:Set<String>?=null
) : LinearLayout(activity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    @Volatile private var generation = 0
    private var identity = ""
    private var signedIn = false
    private var snapshot: HomeworkSnapshot? = null
    private var filter = "未完成"
    private var limit = 30
    private var message = "点“一键更新作业”，读取当前账号的全部课程作业。"
    private var loading = false
    private var visiblePage = false
    private val summary: TextView
    private val progress: TextView
    private val updated: TextView
    private val refresh: Button
    private val cancel: Button
    private val courseChoice: Button
    private val courseFilter=HomeworkCourseFilter(app.prefs)
    private val manualProgress=HomeworkProgress(app.prefs)
    private val filters = mutableListOf<Button>()
    private val records: LinearLayout
    private val tick = object : Runnable {
        override fun run() { if (visiblePage) { render(); main.postDelayed(this, 60_000) } }
    }
    init {
        orientation = VERTICAL
        val header = panel()
        summary = text("尚未更新", 20, ModernUi.ink, true); header.addView(summary)
        progress = text(message, 13, ModernUi.muted); header.addView(progress)
        updated = text("", 12, ModernUi.muted); header.addView(updated)
        refresh = button("一键更新作业", true) { refresh() }; header.addView(refresh)
        cancel = button("取消作业更新") { cancelRefresh() }; header.addView(cancel)
        header.addView(button("打开学习通查看作业") { open(HomeworkParser.WEB_URL) })
        courseChoice=button("选择作业课程") { chooseCourses() };header.addView(courseChoice)
        header.addView(text("使用 App 当前账号直接读取，不依赖手机浏览器；仅手动刷新，不后台轮询。", 12, ModernUi.muted))
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity)
        for (name in listOf("未完成", "已完成", "已过期", "互评", "全部")) {
            val view = button(name) { filter = name; limit = 30; render() }
            view.layoutParams = LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            filters.add(view); row.addView(view)
        }
        strip.addView(row); addView(strip, LayoutParams(-1, -2).apply { topMargin = dp(14) })
        records = LinearLayout(activity).apply { orientation = VERTICAL }; addView(records)
        syncSession()
    }
    fun syncSession() {
        val current = app.prefs.getString("identity", "").orEmpty()
        val loggedIn = app.prefs.getBoolean("loggedIn", false)
        if (identity != current) {
            cancelWork()
            identity = current; limit = 30
            snapshot = if (current.isBlank()) null else cache.read(current)
            message = if (snapshot == null) "点“一键更新作业”，读取当前账号的全部课程作业。" else "已载入上次更新，点击可读取最新状态。"
        }
        signedIn = loggedIn
        render()
    }
    fun setPageVisible(visible: Boolean) {
        visiblePage = visible; main.removeCallbacks(tick)
        if (visible) { syncSession(); main.postDelayed(tick, 60_000) }
    }
    fun close() { cancelWork(); main.removeCallbacksAndMessages(null); worker.shutdownNow() }

    fun refresh() {
        syncSession()
        if (!signedIn || identity.isBlank()) { message = "请先在“我的 → 账号中心”登录学习通。"; render(); goToLogin(); return }
        if (loading) return
        val owner = identity; val token = ++generation; val courses = app.courses();val previous=snapshot
        loading = true; message = "正在读取作业与截止时间，可随时取消…"; render()
        task = worker.submit {
            var result: HomeworkSnapshot? = null
            var failure: Exception? = null
            try {
                DiagnosticLog.event("HomeworkUI", "refresh_begin account=${DiagnosticLog.reference(owner)} courses=${courses.size}")
                val fetched = source.fetch(courses,previous)
                result = fetched
                synchronized(app) {
                    if (token != generation || app.prefs.getString("identity", "") != owner || !app.prefs.getBoolean("loggedIn", false))
                        throw java.util.concurrent.CancellationException()
                    cache.write(owner, fetched)
                    DiagnosticLog.event("HomeworkCache", "saved items=${fetched.items.size}")
                }
            } catch (diagnosticError: java.util.concurrent.CancellationException) { DiagnosticLog.error("HomeworkPanel", diagnosticError); return@submit }
            catch (error: Exception) { DiagnosticLog.error("HomeworkPanel", error); failure = error; DiagnosticLog.event("HomeworkUI", "refresh_failed previous_cache_retained=true", "ERROR") }
            main.post {
                if (token != generation || identity != owner || app.prefs.getString("identity", "") != owner) return@post
                loading = false
                task = null
                if (!app.prefs.getBoolean("loggedIn", false)) { signedIn = false; render(); return@post }
                if (result != null && failure == null) {
                    snapshot = result; message = if(result.skippedRows>0)"部分更新完成，有 ${result.skippedRows} 条未能识别，请在学习通核对。" else "更新完成，已读取全部返回记录。"; limit = 30
                } else {
                    message = if (failure is LoginRequired) "作业会话失效，请在账号中心重新登录；上次记录已保留。"
                        else "更新未完成，上次记录已保留。" + FailureMessages.describe(failure ?: java.io.IOException())
                    if (failure is LoginRequired) synchronized(app) {
                        if (app.prefs.getString("identity", "") == owner) app.prefs.edit().putBoolean("loggedIn", false).apply()
                    }
                }
                signedIn = app.prefs.getBoolean("loggedIn", false); render(); app.notifyUi()
            }
        }
    }
    private fun cancelWork() { generation++; source.cancel(); task?.cancel(true); task = null; loading = false }
    private fun cancelRefresh() { DiagnosticLog.event("HomeworkUI", "cancel_by_user"); cancelWork(); message = "更新已取消，上次记录保留。"; render() }
    private fun open(url: String) {
        val message = when (openLink(url)) {
            HomeworkOpenResult.DIRECT -> return
            HomeworkOpenResult.APP_HOME -> "学习通未接收此内容链接，已打开首页。请点“我的作业”，或从课程进入。"
            HomeworkOpenResult.UNAVAILABLE -> "系统未允许打开学习通。请从桌面打开学习通的作业栏；这里仍可更新和查看列表。"
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
    private fun render() {
        val now = System.currentTimeMillis()
        val items = if(fixedCourses!=null)snapshot?.items.orEmpty().filter{it.courseKey in fixedCourses} else courseFilter.apply(identity,snapshot?.items.orEmpty())
        courseChoice.visibility=if(fixedCourses==null)View.VISIBLE else View.GONE
        val chosen=courseFilter.selection(identity)
        courseChoice.text=if(chosen==null) "选择作业课程 · 全部" else "选择作业课程 · 已选 ${chosen.size} 门"
        val marks=manualProgress.marks(identity)
        fun state(item:HomeworkItem)=HomeworkProgress.state(item,now,marks)
        val works = items.filterNot { it.peerReview }
        val pending = works.count { state(it) == HomeworkState.PENDING }
        val submitted = works.count { state(it) == HomeworkState.SUBMITTED }
        val expired = works.count { state(it) == HomeworkState.EXPIRED }
        val unknown = works.count { state(it) == HomeworkState.UNKNOWN }
        summary.text = if (snapshot == null) "尚未更新作业" else "未完成 $pending　已完成 $submitted　已过期 $expired"
        progress.text = if (!signedIn && !loading) "${if (identity.isBlank()) "请先登录学习通。" else "登录已失效。"}\n$message" else message
        updated.text = snapshot?.let {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(it.fetchedAt))
            val missing=works.count{item->state(item)==HomeworkState.PENDING && item.dueAt==null}
            "上次更新：$time（北京时间）\n作业 ${works.size} 份，另有互评 ${items.count { item -> item.peerReview }} 项${if (unknown > 0) "，$unknown 项状态待确认" else ""}。倒计时依据读取到的截止时间估算。"+
                (if(missing>0)"\n$missing 项未读取到截止时间，请查看详情核对，或稍后再次更新。" else "") +
                (if(it.skippedRows>0)"\n${it.skippedRows} 条记录未识别，${it.retainedKeys.size} 条沿用旧记录，本次未核实状态。" else "")
        }.orEmpty()
        refresh.isEnabled = !loading; refresh.text = if (loading) "正在更新作业…" else "一键更新作业"
        cancel.visibility = if (loading) View.VISIBLE else View.GONE
        filters.forEach { ModernUi.button(it, primaryAction = it.text.toString() == filter) }
        val selected = items.filter {
            when (filter) {
                "未完成" -> !it.peerReview && state(it) == HomeworkState.PENDING
                "已完成" -> !it.peerReview && state(it) == HomeworkState.SUBMITTED
                "已过期" -> !it.peerReview && state(it) == HomeworkState.EXPIRED
                "互评" -> it.peerReview
                else -> true
            }
        }.sortedWith(compareBy<HomeworkItem> { if (state(it) == HomeworkState.PENDING) 0 else 1 }.thenBy { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.title })
        records.removeAllViews()
        if (snapshot == null || selected.isEmpty()) records.addView(text(
            if (snapshot == null) "刷新后会在这里显示作业。" else "上次读取的记录中没有“$filter”作业。", 14, ModernUi.muted))
        for (item in selected.take(limit)) {
            val card = panel(records)
            val urgent=!item.peerReview && state(item)==HomeworkState.PENDING && item.dueAt!=null && item.dueAt>now && item.dueAt-now<=86_400_000L
            if(urgent) {
                card.background=ModernUi.shape(activity,Color.rgb(255,237,237),20,ModernUi.danger)
                card.contentDescription="24 小时内截止的待交作业"
                card.addView(text("即将截止 · 24 小时内",12,ModernUi.danger,true))
            }
            card.addView(text(item.title, 17, ModernUi.ink, true))
            card.addView(text(item.courseName, 13, ModernUi.muted))
            if(item.key in snapshot?.retainedKeys.orEmpty())card.addView(text("上次记录 · 本次未核实状态",12,ModernUi.danger,true))
            val state = state(item)
            val manual=marks[accountKey(item.key)]
            if(item.activityTask)card.addView(text("课程任务",12,ModernUi.primary))
            if(manual!=null) {
                val tone=if(manual==HomeworkMark.COMPLETE) ModernUi.ChoiceTone.COMPLETE else ModernUi.ChoiceTone.INCOMPLETE
                card.addView(text("手动标记：${manual.label} · 仅保存在本机",12,tone.text,true).apply {
                    background=ModernUi.shape(activity,tone.fill,10)
                    setPadding(dp(10),dp(6),dp(10),dp(6))
                    layoutParams=LinearLayout.LayoutParams(-2,-2)
                })
            }
            card.addView(text("学习通：${item.sourceStatus} · ${if (item.peerReview) "互评" else item.state(now).label}", 13,
                if (state == HomeworkState.EXPIRED) ModernUi.danger else ModernUi.primary))
            item.deadlineText()?.let{card.addView(text(it,12,ModernUi.muted))}
            card.addView(text(if(manual==HomeworkMark.COMPLETE)"已手动标记完成，可随时重新标为未完成。" else if(manual==HomeworkMark.INCOMPLETE)"已手动标记未完成，学习通的原始提交状态保留。" else item.remaining(now), 14, if(urgent)ModernUi.danger else ModernUi.ink,urgent))
            card.addView(button(if(item.state(now)==HomeworkState.SUBMITTED)"查看已交作业" else "查看 / 提交作业",true) {OfficialPageActivity.open(activity,item.url,item.title)})
            card.addView(button("设置完成状态") { chooseProgress(item) })
            card.addView(button("在学习通打开") { open(item.url) })
        }
        if (selected.size > limit) records.addView(button("加载更多（还有 ${selected.size - limit} 项）") { limit += 30; render() })
    }
    private fun chooseProgress(item:HomeworkItem) {
        val owner=identity
        val marks=listOf(HomeworkMark.COMPLETE,HomeworkMark.INCOMPLETE,null)
        val selected=marks.indexOf(manualProgress.marks(owner)[accountKey(item.key)])
        ModernUi.dialog(activity,listOf(ModernUi.ChoiceTone.COMPLETE,ModernUi.ChoiceTone.INCOMPLETE,ModernUi.ChoiceTone.NEUTRAL))
            .setTitle("${item.title} · 完成状态")
            .setSingleChoiceItems(arrayOf("标记为已完成","标记为未完成","恢复跟随学习通状态"),selected){dialog,index->
                dialog.dismiss()
                if(owner!=app.prefs.getString("identity","") || !app.prefs.getBoolean("loggedIn",false)){syncSession();return@setSingleChoiceItems}
                diagnosticRunCatching { manualProgress.set(owner,item,marks[index]) }
                    .onSuccess{message="完成状态已保存在本机，刷新不会覆盖；不修改学习通提交记录。";render()}
                    .onFailure{Toast.makeText(activity,"保存标记失败，请重试。",Toast.LENGTH_LONG).show()}
            }.setNegativeButton("取消",null).show()
    }
    private fun chooseCourses() {
        if(identity.isBlank() || !signedIn) { goToLogin();return }
        activity.startActivity(Intent(activity,CoursePickerActivity::class.java).putExtra("mode","homework"))
    }
    private fun dp(value: Int) = ModernUi.dp(activity, value)
    private fun text(value: String, size: Int, color: Int, medium: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun panel(parent: LinearLayout = this) = LinearLayout(activity).apply {
        orientation = VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); background = ModernUi.shape(activity, Color.WHITE, 20)
        parent.addView(this, LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }
    private fun button(title: String, primary: Boolean = false, click: () -> Unit) = Button(activity).apply {
        text = title; ModernUi.button(this, primaryAction = primary); setLoggedClickListener { click() }
        layoutParams = LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
}

class HomeworkCourseFilter(private val prefs:android.content.SharedPreferences) {
    fun selection(owner:String):Set<String>?=prefs.getStringSet("homework.courses.$owner",null)?.toSet()
    fun save(owner:String,keys:Set<String>?) {
        if(owner.isBlank())return
        prefs.edit().apply {if(keys==null)remove("homework.courses.$owner") else putStringSet("homework.courses.$owner",keys)}.apply()
    }
    fun apply(owner:String,items:List<HomeworkItem>):List<HomeworkItem> {
        val keys=selection(owner) ?: return items
        return items.filter{it.courseKey in keys}
    }
}

