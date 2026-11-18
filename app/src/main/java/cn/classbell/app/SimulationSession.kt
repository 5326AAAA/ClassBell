package cn.classbell.app

import java.util.UUID
import android.content.SharedPreferences
import org.json.JSONObject

data class SimulationProgress(
    val nonce: String,
    val deadline: Long,
    val sent: Boolean = false,
    val received: Boolean = false,
    val recognized: Boolean = false,
    val alerted: Boolean = false,
    val finished: Boolean = false,
    val message: String,
    val alarmOnly: Boolean = false,
    val kind: ReminderKind = ReminderKind.SIGN_IN,
) {
    fun description(): String = (if (alarmOnly) listOf(
        "锁屏闹铃测试（不验证通知监听）",
        "① 系统闹钟执行：${if (sent) "已执行" else "等待"}",
        "② 提醒逻辑启动：${if (alerted) "已启动，请确认实际响铃/振动/弹出" else "等待"}",
        message,
    ) else listOf(
        "① 发出系统通知：${if (sent) "已发出" else "等待"}",
        "② 通知监听收到：${if (received) "已收到" else "等待"}",
        "③ 识别${kind.label}内容：${if (recognized) "已识别" else "等待"}",
        "④ 提醒逻辑启动：${if (alerted) "已启动，请确认实际振动/声音" else "等待"}",
        message,
    )).joinToString("\n")
}

/** An explicit, expiring, single-use grant for one notification created by this App. */
class SimulationSession(private val prefs: SharedPreferences? = null) {
    private var progress: SimulationProgress? = diagnosticRunCatching {
        prefs?.getString("simulationProgress", null)?.let { raw -> JSONObject(raw).let {
            SimulationProgress(it.getString("nonce"), it.getLong("deadline"), it.optBoolean("sent"),
                it.optBoolean("received"), it.optBoolean("recognized"), it.optBoolean("alerted"),
                it.optBoolean("finished"), it.optString("message"), it.optBoolean("alarmOnly"), ReminderKind.fromName(it.optString("kind")))
        } }
    }.getOrNull()
        set(value) {
            field = value
            value?.let { p -> prefs?.edit()?.putString("simulationProgress", JSONObject()
                .put("nonce", p.nonce).put("deadline", p.deadline).put("sent", p.sent)
                .put("received", p.received).put("recognized", p.recognized).put("alerted", p.alerted)
                .put("finished", p.finished).put("message", p.message).put("alarmOnly", p.alarmOnly).put("kind", p.kind.name).toString())?.commit() }
        }
    @Synchronized fun snapshot(): SimulationProgress? = progress
    @Synchronized fun begin(now: Long, delayMs: Long, nonce: String = UUID.randomUUID().toString(), alarmOnly: Boolean = false,
                            kind: ReminderKind = ReminderKind.SIGN_IN): String {
        require(delayMs in 0..10_000)
        require(nonce.isNotBlank())
        progress = SimulationProgress(nonce, now + delayMs + 60_000, message = "准备本地${kind.label}测试；不会向学习通发布活动。", alarmOnly = alarmOnly, kind = kind)
        return nonce
    }
    @Synchronized fun active(nonce: String, now: Long): Boolean = progress?.let {
        it.nonce == nonce && !it.finished && now <= it.deadline
    } ?: false
    @Synchronized fun posted(nonce: String, now: Long) {
        if (active(nonce, now)) progress = progress!!.copy(sent = true, message = "系统通知已发出，等待通知监听回调。")
    }
    @Synchronized fun receive(nonce: String, now: Long): Boolean {
        if (!active(nonce, now) || progress!!.received) return false
        progress = progress!!.copy(received = true, message = "通知监听已收到本地测试通知。")
        return true
    }
    @Synchronized fun recognize(nonce: String, now: Long) {
        if (active(nonce, now) && progress!!.received) progress = progress!!.copy(recognized = true, message = "已识别为测试签到，等待启动提醒。")
    }
    @Synchronized fun alert(nonce: String, now: Long) {
        if (active(nonce, now) && (progress!!.recognized || progress!!.alarmOnly)) progress = progress!!.copy(alerted = true,
            message = if (progress!!.alarmOnly) "系统闹钟已启动提醒；请确认锁屏弹出、响铃和振动。此测试不验证通知监听。"
                else "本地通知链路已走通。请确认是否振动、听到“${progress!!.kind.label}”；测试最多提醒 30 秒。")
    }
    @Synchronized fun finish(message: String) { progress = progress?.copy(finished = true, message = message) }
}
