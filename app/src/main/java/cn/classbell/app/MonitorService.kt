package cn.classbell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.content.Context
import org.json.JSONObject

class MonitorService : Service() {
    private val app get() = application as BellApp
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val pending = linkedMapOf<String, PendingReminder>()
    private val gate = NotificationGate()
    private lateinit var vibration: Vibrator
    private lateinit var audio: ReminderAudio
    private lateinit var ringer: ReminderRinger
    private lateinit var queryLock: PowerManager.WakeLock
    private lateinit var dispatchLock: PowerManager.WakeLock
    private lateinit var simulationLock: PowerManager.WakeLock
    private var pollFuture: ScheduledFuture<*>? = null
    private var pollingActive = false
    private var queryQueued = false
    private var failures = 0
    private var lastPollSummary: String? = null
    private var backend: ActivityReceiver? = null
    @Volatile private var stopped = false
    private var test = false
    private var testKind = ReminderKind.SIGN_IN
    private var testGeneration = 0
    private var alertEpoch = 0
    private var startedAt = 0L
    private var lastEventQuery = -10_000L
    private var deferredNotificationCheck = false
    private val notificationCheck = Runnable {
        if (!stopped && running && !queryQueued && canQuery(app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY))) {
            deferredNotificationCheck = false
            lastEventQuery = SystemClock.elapsedRealtime()
            requestQuery(QueryReason.NOTIFICATION, alertEpoch)
        } else releaseDispatchLock()
    }
    private var stopReason: String? = null
    private val healthCheck = object : Runnable {
        override fun run() {
            if (stopped || !running || simulationOnly || !app.prefs.getBoolean("monitorEnabled", false)) return
            ListenerRecovery.ensure(this@MonitorService)
            if (pending.isEmpty() && !test) waitingStatus()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        @Suppress("DEPRECATION")
        vibration = getSystemService(VIBRATOR_SERVICE) as Vibrator
        audio = ReminderAudio(this, { app.prefs.getBoolean("voiceEnabled", true) }, { message -> AlertTrace.add(this, message) },
            kinds = { currentKinds().toList() })
        ringer = ReminderRinger(this, onStarted = { AlertTrace.add(this, "循环铃声已启动，使用系统闹钟音量") },
            onFailure = { message -> AlertTrace.add(this, message) })
        UrgentAlert.ensureChannel(this)
        queryLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:active-query").apply { setReferenceCounted(false) }
        dispatchLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:notification-query-handoff").apply { setReferenceCounted(false) }
        simulationLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:explicit-local-test").apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "签到与课堂活动监测状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示通知监听和备用轮询状态，并提供停止按钮"
                setSound(null, null); enableVibration(false)
            })
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) { stopSelf(); return START_NOT_STICKY }
        val action = intent?.action ?: if (app.prefs.getBoolean("monitorEnabled", false)) START else null
        when (action) {
            STOP -> {
                stopMonitoring(this)
                return START_NOT_STICKY
            }
            ACK -> {
                app.acknowledge(pending.keys.filterNot { it.startsWith("simulation:") })
                alertEpoch++
                deferredNotificationCheck = false
                main.removeCallbacks(notificationCheck)
                releaseDispatchLock()
                pending.clear(); stopVibration(); saveAlert()
                AlarmScheduler.cancel(this)
                if (app.simulation.snapshot()?.finished == false) {
                    app.simulation.finish("本地模拟提醒已手动停止。")
                    SimulationNotification.clear(this)
                    if (simulationLock.isHeld) simulationLock.release()
                }
                if (!running || simulationOnly) { stopReason = "提醒已停止"; stopSelf() } else {
                    AlertTrace.add(this, "已停止本次声音和振动，后台监测继续")
                    waitingStatus()
                }
                return restartMode()
            }
            SIMULATE, ALARM_TEST -> {
                showForeground()
                if (action == SIMULATE && !XxtNotificationListener.hasAccess(this)) {
                    stopReason = "本地通知测试也需要通知使用权，请先开启。"
                    if (!running) stopSelf() else app.setStatus(stopReason!!)
                    return START_NOT_STICKY
                }
                if (!running) {
                    running = true
                    simulationOnly = true
                    pollingActive = false
                    startedAt = System.currentTimeMillis()
                }
                beginSimulation(if (action == ALARM_TEST) 10_000 else intent?.getLongExtra(EXTRA_SIMULATION_DELAY, 0)?.coerceIn(0, 10_000) ?: 0,
                    action == ALARM_TEST, ReminderKind.fromName(intent?.getStringExtra(EXTRA_SIMULATION_KIND)))
                return restartMode()
            }
            FIRE_TEST -> {
                showForeground()
                val nonce = intent?.getStringExtra(AlarmScheduler.EXTRA_NONCE)
                if (nonce == null || !app.simulation.active(nonce, SystemClock.elapsedRealtime())) {
                    if (!running) stopSelf()
                    return restartMode()
                }
                if (!running) { running = true; simulationOnly = !app.prefs.getBoolean("monitorEnabled", false); startedAt = System.currentTimeMillis() }
                simulationLock.acquire(35_000)
                if (app.simulation.snapshot()?.alarmOnly == true) {
                    app.simulation.posted(nonce, SystemClock.elapsedRealtime())
                    addPending(mapOf("simulation:$nonce" to PendingReminder("【本地测试】锁屏闹铃测试", app.simulation.snapshot()!!.kind)))
                    app.simulation.alert(nonce, SystemClock.elapsedRealtime())
                    finishSimulationLater(nonce)
                    app.notifyUi()
                } else publishSimulationWhenReady(nonce, 0, 0)
                return restartMode()
            }
            CANCEL_SIMULATION -> {
                endSimulation("本地模拟测试已取消。")
                return restartMode()
            }
            TEST, TEST_CLASSROOM -> {
                showForeground()
                if (pending.isEmpty()) {
                    test = true
                    testKind = if (action == TEST_CLASSROOM) ReminderKind.CLASSROOM else ReminderKind.SIGN_IN
                    vibrate()
                    val token = ++testGeneration
                    app.prefs.edit().putString("alert", "${testKind.label}测试中，5 秒后自动停止").apply()
                    main.postDelayed({
                        if (test && testGeneration == token) {
                            stopVibration(); saveAlert()
                            if (!running) stopSelf() else waitingStatus()
                        }
                    }, 5000)
                    app.notifyUi()
                }
                return restartMode()
            }
            START, SIGNAL, MonitorRecovery.RESTORE -> {
                showForeground()
                if (action != START && !app.prefs.getBoolean("monitorEnabled", false)) {
                    if (!running) stopSelf()
                    return restartMode()
                }
                if (running) {
                    if (!simulationOnly) ListenerRecovery.ensure(this)
                    SignalInbox.take(this).forEach { onSignal(it) }
                    return restartMode()
                }
                pollingActive = app.prefs.getBoolean("fallbackPolling", NotificationRules.DEFAULT_POLLING)
                if (pollingActive && !canQuery(true)) {
                    stopReason = "开启备用轮询需要先登录并选择课程"
                    stopSelf(); return START_NOT_STICKY
                }
                if (!XxtNotificationListener.hasAccess(this) && !pollingActive && !(app.prefs.getBoolean("backendPush",false) && canQuery(true))) {
                    stopReason = "请先开启学习通签到通知的通知使用权"
                    stopSelf(); return START_NOT_STICKY
                }
                running = true
                simulationOnly = false
                startedAt = if (action != START || intent == null) app.prefs.getLong("monitorStartedAt", System.currentTimeMillis()) else System.currentTimeMillis()
                app.prefs.edit().putBoolean("monitorEnabled", true).putLong("monitorStartedAt", startedAt).apply()
                MonitorRecovery.clearNotice(this)
                MonitorRecovery.schedule(this)
                main.removeCallbacks(healthCheck)
                main.post(healthCheck)
                AlertTrace.add(this, if (action == MonitorRecovery.RESTORE || intent == null) "监测服务已恢复" else "正式监测已开启")
                waitingStatus()
                restorePending()
                SignalInbox.take(this).forEach { onSignal(it) }
                // Local health checks do not wake a sleeping CPU or query the network.
                if (pollingActive) schedulePoll(0)
                if(app.prefs.getBoolean("backendPush",false) && canQuery(true)) {
                    backend=BackendImClient(app) { activity -> main.post {
                        if(!stopped && running && !simulationOnly && app.prefs.getBoolean("backendPush",false) && canQuery(true)) {
                            val result = ActivityReminderCounts()
                            acceptActivities(listOf(activity), result)
                            if (result.fresh > 0) AlertTrace.add(this, "直接获取动态 · 新增${activity.kind.label}提醒")
                            worker.execute { app.signing.autoSubmit(activity) { !stopped && app.prefs.getBoolean("monitorEnabled",false) } }
                        }
                    } }.also {it.start()}
                }
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return restartMode()
    }
    private fun restartMode() = if (app.prefs.getBoolean("monitorEnabled", false)) START_STICKY else START_NOT_STICKY

    private fun onSignal(signal: SignSignal) {
        if (signal.simulationId != null && !app.simulation.active(signal.simulationId, SystemClock.elapsedRealtime())) return
        if (signal.kind == ReminderKind.CLASSROOM && !app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)) return
        if (!running || stopped) return
        val acknowledged = app.acknowledged()
        if (!gate.accept(signal, SystemClock.elapsedRealtime(), startedAt, acknowledged)) {
            if (signal.simulationId == null) AlertTrace.add(this, when {
                signal.postedAt < startedAt -> "学习通通知早于本次监测，未触发提醒"
                signal.key in acknowledged -> "学习通通知已确认，未重复提醒"
                else -> "收到重复的学习通通知，未重复提醒"
            })
            return
        }
        if (signal.kind == null) { scheduleNotificationCheck(); return }
        app.prefs.edit().putLong("lastSignal", System.currentTimeMillis()).apply()
        addPending(mapOf(signal.key to PendingReminder("${if (signal.simulationId == null) "学习通通知" else "本地测试通知"} · ${signal.kind.label} · ${signal.description}", signal.kind)))
        if (signal.simulationId != null) {
            val nonce = signal.simulationId
            app.simulation.alert(nonce, SystemClock.elapsedRealtime())
            finishSimulationLater(nonce)
            app.setStatus("本地模拟通知已触发提醒，请确认振动和语音；最多 30 秒自动结束。")
            updateNotification()
            return // Never use a local simulated event to query the learning account.
        }
        if (canQuery(app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY))) scheduleNotificationCheck()
        else {
            app.setStatus("已收到${signal.kind.label}通知并提醒；未进行接口确认")
            updateNotification()
        }
    }
    private fun scheduleNotificationCheck() {
        if (!running || stopped || !canQuery(app.prefs.getBoolean("verifyOnNotification", NotificationRules.DEFAULT_VERIFY))) {
            deferredNotificationCheck = false
            main.removeCallbacks(notificationCheck)
            releaseDispatchLock()
            return
        }
        deferredNotificationCheck = true
        main.removeCallbacks(notificationCheck)
        if (!queryQueued) {
            val delay = (10_000 - (SystemClock.elapsedRealtime() - lastEventQuery)).coerceAtLeast(0)
            // A short event-driven handoff, not an idle monitoring lock. Prevents a locked-screen
            // cooldown timer from waiting until the next unlock; the worker takes over with queryLock.
            synchronized(dispatchLock) { dispatchLock.acquire(delay + 15_000) }
            main.postDelayed(notificationCheck, delay)
        }
    }

    private fun finishSimulationLater(nonce: String) {
        simulationLock.acquire(35_000)
        main.postDelayed({
            if (app.simulation.active(nonce, SystemClock.elapsedRealtime())) endSimulation("本地模拟提醒已在 30 秒后自动结束。")
        }, 30_000)
    }
    private fun beginSimulation(delayMs: Long, alarmOnly: Boolean = false, kind: ReminderKind = ReminderKind.SIGN_IN) {
        AlarmScheduler.cancel(this)
        pending.keys.filter { it.startsWith("simulation:") }.forEach { pending.remove(it) }
        if (pending.isEmpty()) stopVibration()
        saveAlert()
        SimulationNotification.clear(this)
        val nonce = app.simulation.begin(SystemClock.elapsedRealtime(), delayMs, alarmOnly = alarmOnly, kind = kind)
        if (delayMs > 0) {
            try {
                AlarmScheduler.schedule(this, nonce, delayMs)
                app.setStatus("已设定 10 秒后的系统闹钟，现在可以锁屏。系统可能显示闹钟图标。")
            } catch (e: Exception) { DiagnosticLog.error("MonitorService", e); endSimulation(e.message ?: "系统闹钟设置失败，请检查闹钟和提醒权限。") }
            return
        }
        simulationLock.acquire(35_000)
        app.setStatus("正在准备本地模拟通知测试，等待通知监听连接…")
        publishSimulationWhenReady(nonce, delayMs, 0)
    }
    private fun publishSimulationWhenReady(nonce: String, delayMs: Long, attempts: Int) {
        if (stopped || !running || !app.simulation.active(nonce, SystemClock.elapsedRealtime())) return
        if (!XxtNotificationListener.connected) {
            if (attempts == 0) ListenerRecovery.ensure(this)
            if (attempts >= 20) { endSimulation("通知监听仍在重连。请点“修复通知连接”，检查后台运行设置后再测试，无需反复开关通知使用权。"); return }
            main.postDelayed({ publishSimulationWhenReady(nonce, delayMs, attempts + 1) }, 500)
            return
        }
        app.setStatus(if (delayMs > 0) "10 秒后发出本地测试通知，现在可以锁屏。" else "准备发出本地测试通知…")
        main.postDelayed({
            if (stopped || !running || !app.simulation.active(nonce, SystemClock.elapsedRealtime())) return@postDelayed
            try {
                val manager = getSystemService(NotificationManager::class.java)
                if (!manager.areNotificationsEnabled()) throw IllegalStateException("Notifications disabled")
                SimulationNotification.publish(this, nonce, app.simulation.snapshot()?.kind ?: ReminderKind.SIGN_IN)
                app.simulation.posted(nonce, SystemClock.elapsedRealtime())
                app.setStatus("本地测试通知已发出，等待系统通知监听回调。")
                main.postDelayed({
                    val progress = app.simulation.snapshot()
                    if (progress?.nonce == nonce && !progress.finished && !progress.alerted) {
                        endSimulation(if (!progress.received) "测试通知已发出，但未收到监听回调。请检查通知使用权与系统通知设置。"
                            else "监听已收到，但提醒未触发。请保留本页测试进度。")
                    }
                }, 15_000)
            } catch (diagnosticError: Exception) { DiagnosticLog.error("MonitorService", diagnosticError); endSimulation("模拟通知未能发出，请允许本 App 显示通知，并开启“本地模拟签到通知”分类后重试。") }
        }, delayMs)
    }
    private fun endSimulation(message: String) {
        AlarmScheduler.cancel(this)
        app.simulation.finish(message)
        SimulationNotification.clear(this)
        if (simulationLock.isHeld) simulationLock.release()
        pending.keys.filter { it.startsWith("simulation:") }.forEach { pending.remove(it) }
        if (pending.isEmpty()) stopVibration()
        saveAlert()
        if (simulationOnly && pending.isEmpty()) {
            stopReason = message
            stopSelf()
        } else if (running) {
            simulationOnly = false
            app.setStatus(message)
            updateNotification()
        }
    }

    private fun canQuery(enabled: Boolean) = NotificationRules.shouldQuery(enabled,
        app.prefs.getBoolean("loggedIn", false), app.selectedCourses().size)

    private fun schedulePoll(delaySeconds: Long) {
        if (!running || stopped || !pollingActive) return
        pollFuture?.cancel(false)
        pollFuture = worker.schedule({ main.post { requestQuery(QueryReason.POLL, alertEpoch) } }, delaySeconds, TimeUnit.SECONDS)
    }

    /** Main-thread admission coalesces notification bursts into at most one outstanding query. */
    private fun requestQuery(reason: QueryReason, epoch: Int) {
        if (!running || stopped || !canQuery(true)) return
        if (queryQueued) {
            if (reason == QueryReason.POLL) schedulePoll(app.prefs.getInt("interval", 60).toLong())
            return
        }
        queryQueued = true
        if (reason == QueryReason.NOTIFICATION) app.setStatus("正在按学习通通知检查所选课程的签到与课堂活动…")
        else app.setStatus("正在执行备用轮询…")
        updateNotification()
        worker.execute { query(reason, epoch) }
    }

    private fun query(reason: QueryReason, epoch: Int) {
        val courses = app.selectedCourses()
        var successful = 0
        var foundCount = 0
        val reminders = ActivityReminderCounts() // Read and updated only by the main-thread callbacks below.
        var problem = ""
        var expired = false
        try {
            for (course in courses) {
                if (stopped || Thread.currentThread().isInterrupted) return
                // A bounded lock is held only during active queries, never during idle waits.
                synchronized(queryLock) { if (!stopped) queryLock.acquire(90_000L) }
                releaseDispatchLock()
                try {
                    DiagnosticLog.event("MonitorCourse", "${DiagnosticLog.course(course)} begin trigger=$reason")
                    val found = app.api.activities(course)
                    foundCount += found.size
                    successful++
                    main.post {
                        if (!stopped && running && (reason == QueryReason.POLL || epoch == alertEpoch)) acceptActivities(found, reminders)
                    }
                    // Only authenticated course API results enter this opt-in path; never raw/simulated notifications.
                    found.forEach { activity ->
                        if (!stopped && app.prefs.getBoolean("monitorEnabled", false)) app.signing.autoSubmit(activity) {
                            !stopped && app.prefs.getBoolean("monitorEnabled", false)
                        }
                    }
                } catch (diagnosticError: LoginRequired) { DiagnosticLog.event("MonitorCourse", "${DiagnosticLog.course(course)} failed reason=login_expired remaining_courses_skipped=true", "WARN");DiagnosticLog.error("MonitorService", diagnosticError); expired = true; break }
                catch (e: ApiProblem) { DiagnosticLog.event("MonitorCourse", "${DiagnosticLog.course(course)} failed continue_next=true", "WARN");DiagnosticLog.error("MonitorService", e); problem = e.message ?: "查询失败" }
                catch (diagnosticError: Exception) { DiagnosticLog.event("MonitorCourse", "${DiagnosticLog.course(course)} failed category=${DiagnosticLog.failureKind(diagnosticError)} continue_next=true", "WARN");DiagnosticLog.error("MonitorService", diagnosticError); problem = "网络连接失败" }
                try { Thread.sleep(350) } catch (diagnosticError: InterruptedException) { DiagnosticLog.error("MonitorService", diagnosticError); return }
            }
        } finally { releaseQueryLock(); releaseDispatchLock() }
        main.post {
            queryQueued = false
            if (stopped || !running) return@post
            if (expired) {
                app.prefs.edit().putBoolean("loggedIn", false).putBoolean("fallbackPolling", false).apply()
                pollingActive = false
                deferredNotificationCheck = false
                pollFuture?.cancel(false)
                // An expired account must not stop notification alerts or an active vibration.
                app.setStatus("账号登录已失效，接口查询已暂停；通知提醒继续运行")
                updateNotification()
                return@post
            }
            val now = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            val result = "进行中 ${(foundCount-reminders.ignored).coerceAtLeast(0)} 项 · 本轮新增提醒 ${reminders.fresh} 项" + reminders.skippedSummary()
            val detail = if (successful == courses.size) result else "检查成功 $successful/${courses.size} 门 · $problem · $result"
            app.prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            if (reason == QueryReason.NOTIFICATION) {
                // Supplementary course information, not proof of an exact notification/activity match.
                if (epoch == alertEpoch) {
                    app.setStatus("$now 通知后核对 · $detail")
                    AlertTrace.add(this, "通知后核对 · $detail")
                }
            } else {
                failures = if (successful == courses.size) 0 else failures + 1
                val delay = ReminderRules.retryDelaySeconds(failures, app.prefs.getInt("interval", 60))
                app.setStatus("$now 备用轮询 · $detail · ${delay} 秒后再查")
                if (detail != lastPollSummary) {
                    lastPollSummary = detail
                    AlertTrace.add(this, "备用轮询 · $detail")
                }
                schedulePoll(delay)
            }
            updateNotification()
            if (deferredNotificationCheck) scheduleNotificationCheck()
        }
    }

    private fun acceptActivities(found: List<SignActivity>, result: ActivityReminderCounts) {
        val classroomEnabled = app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM)
        val owner=app.prefs.getString("identity","").orEmpty()
        val ignored=found.filter{it.kind==ReminderKind.SIGN_IN && app.signing.ledger.ignored(owner,it.key)}.toSet()
        val enabled = found.filter { it !in ignored && (it.kind != ReminderKind.CLASSROOM || classroomEnabled) }
        val acknowledged = app.acknowledged()
        val fresh = ReminderRules.unseen(enabled, acknowledged, pending.keys)
        found.forEach{item->DiagnosticLog.event("ReminderDecision", "${DiagnosticLog.activity(item)} outcome=${when{item in ignored->"ignored_by_user";item !in enabled->"disabled";item.key in acknowledged->"acknowledged";item.key in pending->"already_alerting";else->"new_reminder"}}") }
        result.fresh += fresh.size
        result.ignored += ignored.size
        result.disabled += found.size - enabled.size - ignored.size
        result.confirmed += enabled.count { it.key in acknowledged }
        result.alreadyAlerting += enabled.count { it.key !in acknowledged && it.key in pending }
        addPending(fresh.associate { it.key to PendingReminder("${it.kind.label} · ${it.course.name} · ${it.title}", it.kind) })
    }
    private fun addPending(items: Map<String, PendingReminder>) {
        if (items.isEmpty()) return
        val first = pending.isEmpty()
        // New alerts must not invalidate results for later courses in the same query. Only ACK/stop does.
        pending.putAll(items)
        while (pending.size > 128) pending.remove(pending.keys.first())
        val wasTest = test
        test = false; testGeneration++
        saveAlert()
        if (first || wasTest) vibrate() else {
            updateAudio(); UrgentAlert.publish(this, currentKinds()); updateNotification()
        }
    }
    private fun saveAlert() {
        val lines = pending.values.take(8).joinToString("\n") { it.text }
        val message = if (pending.size > 8) "$lines\n…共 ${pending.size} 条提醒" else lines
        val durable = JSONObject()
        pending.filterKeys { !it.startsWith("simulation:") }.forEach { (key, value) ->
            durable.put(key, JSONObject().put("text", value.text).put("kind", value.kind.name))
        }
        app.prefs.edit().putString("alert", message).putString("pendingAlerts", durable.toString()).apply()
        app.notifyUi()
    }
    private fun restorePending() {
        val data = diagnosticRunCatching { JSONObject(app.prefs.getString("pendingAlerts", "{}").orEmpty()) }.getOrDefault(JSONObject())
        val owner=app.prefs.getString("identity","").orEmpty()
        val acknowledged=app.acknowledged()
        val restored = data.keys().asSequence().take(128)
            .filter { it !in acknowledged && !app.signing.ledger.ignored(owner,it) }.map { key ->
            val value = data.optJSONObject(key)
            key to if (value == null) PendingReminder(data.optString(key)) else
                PendingReminder(value.optString("text"), ReminderKind.fromName(value.optString("kind")))
        }.filter { it.second.kind != ReminderKind.CLASSROOM || app.prefs.getBoolean("classroomEnabled", NotificationRules.DEFAULT_CLASSROOM) }.toMap()
        addPending(restored)
    }
    private fun waitingStatus() {
        if (!running || stopped) return
        if (simulationOnly) {
            app.setStatus("本地模拟测试模式，不登录、不查询学习通接口。")
            updateNotification()
            return
        }
        val listener = when {
            !XxtNotificationListener.hasAccess(this) && app.prefs.getBoolean("backendPush",false) -> "后端消息监听运行中"
            !XxtNotificationListener.hasAccess(this) -> "通知使用权未开启"
            !XxtNotificationListener.connected -> "通知已授权，正在自动重连（无需重新开关权限）"
            else -> "等待学习通签到 / 课堂活动通知"
        }
        val poll = if (pollingActive) "备用轮询已开启" else "备用轮询关闭，等待时不查询接口"
        app.setStatus("$listener · $poll")
        updateNotification()
    }
    private fun releaseQueryLock() { synchronized(queryLock) { if (queryLock.isHeld) queryLock.release() } }
    private fun releaseDispatchLock() { synchronized(dispatchLock) { if (dispatchLock.isHeld) dispatchLock.release() } }
    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 1000, 500), 0)
            if (Build.VERSION.SDK_INT >= 33) vibration.vibrate(effect,
                android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_ALARM).build())
            else vibration.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
        } catch (diagnosticError: Exception) { DiagnosticLog.error("MonitorService", diagnosticError); app.setStatus("系统未允许振动，请检查手机振动和勿扰设置") }
        updateAudio()
        UrgentAlert.publish(this, currentKinds())
        val alarmVolume = getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        val ring = if (app.prefs.getBoolean("ringEnabled", true)) "循环响铃已开启" else "循环响铃已关闭"
        val voice = if (app.prefs.getBoolean("voiceEnabled", true)) "语音已开启" else "语音已关闭"
        AlertTrace.add(this, "已触发提醒 · $ring · $voice · 闹钟音量 $alarmVolume")
        updateNotification()
    }
    private fun updateAudio() {
        if ((pending.isNotEmpty() || test) && app.prefs.getBoolean("voiceEnabled", true)) audio.start() else audio.stop()
        if ((pending.isNotEmpty() || test) && app.prefs.getBoolean("ringEnabled", true))
            ringer.start(app.prefs.getBoolean("voiceEnabled", true)) else ringer.stop()
    }
    private fun currentKinds(): Set<ReminderKind> = if (test) setOf(testKind)
        else pending.values.map { it.kind }.toSortedSet(compareBy { it.ordinal })
    private fun stopVibration() {
        testGeneration++; test = false; diagnosticRunCatching { vibration.cancel() }; audio.stop(); ringer.stop()
        UrgentAlert.clear(this)
    }
    private fun showForeground() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(action: String, request: Int) = PendingIntent.getBroadcast(this, request,
            Intent(this, AlertActionReceiver::class.java).setAction(action)
                .putExtra(UrgentAlert.EXTRA_TOKEN, app.prefs.getString("activeAlertToken", null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (pending.isNotEmpty()) "收到${currentKinds().joinToString(" / ") { it.label }}提醒" else if (test) "测试提醒" else "正在监测学习通 · 状态通知"
        val text = pending.values.firstOrNull()?.text ?: app.prefs.getString("status", "正在准备监测")
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title).setContentText(text).setContentIntent(open)
            .setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (pending.isNotEmpty() || test) builder.addAction(Notification.Action.Builder(null, "停止声音和振动", action(ACK, 2)).build())
        return builder.addAction(Notification.Action.Builder(null, "停止监测", action(STOP, 3)).build()).build()
    }
    private fun updateNotification() {
        if (!stopped) getSystemService(NotificationManager::class.java).notify(ID, notification())
    }
    private fun stopMonitoringNow() {
        // Mark stopped before draining callbacks; ACK must not publish a new waiting notification.
        stopped = true; running = false; simulationOnly = false
        stopReason = "监测已停止"
        alertEpoch++; deferredNotificationCheck = false
        main.removeCallbacksAndMessages(null)
        val receiver=backend;backend=null
        diagnosticRunCatching { receiver?.stop() }
        app.signing.cancelAutomatic()
        app.api.cancelRequests()
        pollFuture?.cancel(false)
        worker.shutdownNow()
        pending.clear()
        stopVibration()
        releaseQueryLock(); releaseDispatchLock()
        if (simulationLock.isHeld) simulationLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        stopSelf()
    }
    override fun onDestroy() {
        stopped = true; running = false
        val receiver=backend;backend=null
        diagnosticRunCatching { receiver?.stop() }
        app.signing.cancelAutomatic()
        simulationOnly = false
        if (instance === this) instance = null
        alertEpoch++
        stopVibration()
        pollFuture?.cancel(false)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        releaseQueryLock()
        releaseDispatchLock()
        if (simulationLock.isHeld) simulationLock.release()
        if (app.simulation.snapshot()?.finished == false && app.prefs.getString("scheduledTestNonce", null) == null)
            app.simulation.finish("本地模拟测试已停止。")
        SimulationNotification.clear(this)
        if (!app.prefs.getBoolean("monitorEnabled", false)) app.prefs.edit().putString("alert", "").putString("pendingAlerts", "{}").apply()
        val enabled = app.prefs.getBoolean("monitorEnabled", false)
        AlertTrace.add(this, if (enabled) "监测服务被结束，保留启用状态等待系统恢复" else "监测已由用户停止")
        if (!enabled) MonitorRecovery.cancel(this)
        app.setStatus(stopReason ?: if (enabled) "监测暂时中断，等待系统恢复；打开 App 可自动恢复" else "监测已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (app.prefs.getBoolean("monitorEnabled", false)) {
            AlertTrace.add(this, "界面已从最近任务移除，监测保持开启")
            MonitorRecovery.schedule(this)
            ListenerRecovery.ensure(this)
        }
        super.onTaskRemoved(rootIntent)
    }
    private enum class QueryReason { NOTIFICATION, POLL }
    private class ActivityReminderCounts {
        var fresh = 0
        var confirmed = 0
        var alreadyAlerting = 0
        var disabled = 0
        var ignored = 0

        fun skippedSummary() = buildString {
            if (confirmed > 0) append(" · 已确认 $confirmed 项")
            if (alreadyAlerting > 0) append(" · 已在提醒 $alreadyAlerting 项")
            if (disabled > 0) append(" · 课堂活动提醒已关闭 $disabled 项")
            if (ignored > 0) append(" · 已跳过 $ignored 项签到")
        }
    }
    companion object {
        @Volatile var running = false
            private set
        @Volatile var simulationOnly = false
            private set
        @Volatile private var instance: MonitorService? = null
        fun dismissIgnoredActivity(owner:String,key:String) {
            instance?.let { service -> service.main.post {
                if(service.stopped || owner!=service.app.prefs.getString("identity","") ||
                    !service.app.signing.ledger.ignored(owner,key) || service.pending.remove(key)==null)return@post
                if(service.pending.isEmpty()) service.stopVibration() else {
                    service.updateAudio()
                    UrgentAlert.publish(service,service.currentKinds())
                }
                service.saveAlert()
                if(service.pending.isEmpty())service.waitingStatus() else service.updateNotification()
            } }
        }
        fun stopMonitoring(context: Context) {
            val app = context.applicationContext as BellApp
            app.prefs.edit().putBoolean("monitorEnabled", false).remove("signalInbox")
                .putString("alert", "").putString("pendingAlerts", "{}").commit()
            MonitorRecovery.cancel(context)
            AlarmScheduler.cancel(context)
            if (app.simulation.snapshot()?.finished == false) app.simulation.finish("监测已停止，本地测试已取消。")
            SimulationNotification.clear(context)
            UrgentAlert.clear(context)
            instance?.let { service ->
                if (Looper.myLooper() == Looper.getMainLooper()) service.stopMonitoringNow()
                else service.main.post { service.stopMonitoringNow() }
            }
            context.getSystemService(NotificationManager::class.java).cancel(ID)
            context.stopService(Intent(context, MonitorService::class.java))
            app.setStatus("监测已停止")
        }
        fun deliver(context: Context, signal: SignSignal) {
            instance?.takeIf { !it.stopped && running }?.let { service -> service.main.post { service.onSignal(signal) }; return }
            val app = context.applicationContext as BellApp
            if (signal.simulationId != null || !app.prefs.getBoolean("monitorEnabled", false) ||
                signal.postedAt < app.prefs.getLong("monitorStartedAt", 0) || signal.key in app.acknowledged()) return
            SignalInbox.enqueue(context, signal)
            signal.kind?.let { UrgentAlert.publish(context, SignalInbox.read(context).mapNotNull { it.kind }.toSet()) }
            resumeQueued(context)
        }
        fun resumeQueued(context: Context) {
            if (SignalInbox.read(context).isEmpty()) return
            try { context.startForegroundService(Intent(context, MonitorService::class.java).setAction(SIGNAL)) }
            catch (diagnosticError: Exception) { DiagnosticLog.error("MonitorService", diagnosticError);
                AlertTrace.add(context, "后台提醒服务启动被拦截；已识别事件尝试紧急通知，未分类事件需打开 App 后检查")
            }
        }
        fun acknowledge(context: Context) {
            val app = context.applicationContext as BellApp
            app.acknowledge(SignalInbox.take(context).map { it.key })
            val data = diagnosticRunCatching { JSONObject(app.prefs.getString("pendingAlerts", "{}").orEmpty()) }.getOrDefault(JSONObject())
            app.acknowledge(data.keys().asSequence().toList())
            app.prefs.edit().putString("alert", "").putString("pendingAlerts", "{}").apply()
            AlarmScheduler.cancel(context)
            UrgentAlert.clear(context)
            instance?.let { service -> service.main.post { service.onStartCommand(Intent(context, MonitorService::class.java).setAction(ACK), 0, 0) } }
            app.notifyUi()
        }
        fun listenerChanged() { instance?.let { service -> service.main.post { if (service.pending.isEmpty()) service.waitingStatus() } } }
        fun refreshAudioSettings() { instance?.let { service -> service.main.post { service.updateAudio() } } }
        const val START = "start"
        const val STOP = "stop"
        const val ACK = "ack"
        const val TEST = "test"
        const val TEST_CLASSROOM = "test_classroom"
        const val SIMULATE = "simulate"
        const val ALARM_TEST = "alarm_test"
        const val FIRE_TEST = "fire_test"
        const val SIGNAL = "signal"
        const val CANCEL_SIMULATION = "cancel_simulation"
        const val EXTRA_SIMULATION_DELAY = "simulation_delay"
        const val EXTRA_SIMULATION_KIND = "simulation_kind"
        private const val CHANNEL = "monitor"
        private const val ID = 7
    }
}
