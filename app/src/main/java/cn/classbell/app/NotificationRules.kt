package cn.classbell.app

data class SignSignal(
    val sourceKey: String,
    val title: String,
    val text: String,
    val eventTime: Long,
    val postedAt: Long,
    val simulationId: String? = null,
    val kind: ReminderKind? = ReminderKind.SIGN_IN,
) {
    val key: String get() = "${if (simulationId == null) "notification" else "simulation"}:${accountKey("$sourceKey|$eventTime|$title|$text")}"
    val contentKey: String get() = accountKey("$sourceKey|$title|$text")
    val description: String get() = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").take(400)
}

object NotificationRules {
    const val SOURCE_PACKAGE = "com.chaoxing.mobile"
    const val DEFAULT_POLLING = false
    const val DEFAULT_VERIFY = true
    const val DEFAULT_CLASSROOM = true
    private val completed = listOf("签到成功", "已完成签到", "完成了签到", "你已签到", "您已签到", "签到已结束", "签到结束", "签到已过期", "已结束签到", "无需签到", "不需要签到")

    fun isCandidate(packageName: String, title: String, text: String, groupSummary: Boolean = false): Boolean {
        return packageName == SOURCE_PACKAGE && matchesContent(title, text, groupSummary)
    }
    fun matchesContent(title: String, text: String, groupSummary: Boolean = false): Boolean {
        return classify(title, text, groupSummary) != null
    }
    private fun message(title: String, text: String) = "$title $text".replace(Regex("[\\s\\u200B\\u200C\\u200D]+"), "")
    private val classroomTerms = listOf("课堂活动", "随堂练习", "课堂练习", "随堂测验", "课堂测验")
    private val classroomFinished = listOf("活动已结束", "活动结束", "活动已过期", "练习已结束", "练习结束", "测验已结束", "已完成课堂活动", "已完成随堂练习", "提交成功", "已提交")
    fun classify(title: String, text: String, groupSummary: Boolean = false): ReminderKind? {
        if (groupSummary) return null
        val message = message(title, text)
        // A generic "课堂活动" title must not reinterpret a completed sign-in as a new exercise.
        if (completed.any { message.contains(it) } || classroomFinished.any { message.contains(it) }) return null
        if (message.contains("签到")) return ReminderKind.SIGN_IN
        return if (classroomTerms.any { message.contains(it) }) ReminderKind.CLASSROOM else null
    }
    fun mayInspectUnclassified(title: String, text: String, groupSummary: Boolean = false): Boolean {
        val message = message(title, text)
        return !groupSummary && message.isNotBlank() && completed.none { message.contains(it) } &&
            classroomFinished.none { message.contains(it) }
    }

    /** MessagingStyle/InboxStyle history must not make an unrelated new message look like a sign-in. */
    fun latestText(messages: List<String>, lines: List<String>, expanded: String, plain: String): String =
        messages.lastOrNull { it.isNotBlank() }
            ?: lines.lastOrNull { it.isNotBlank() }
            ?: expanded.ifBlank { plain }

    fun shouldQuery(enabled: Boolean, loggedIn: Boolean, courseCount: Int): Boolean = enabled && loggedIn && courseCount > 0
}

/** Bounded in-memory coalescing for repeated notification updates; never retains unrelated notifications. */
class NotificationGate(private val capacity: Int = 128, private val cooldownMs: Long = 30_000) {
    private val recent = linkedMapOf<String, Long>()
    fun accept(signal: SignSignal, now: Long, startedAt: Long, acknowledged: Set<String>): Boolean {
        if (signal.postedAt < startedAt || signal.key in acknowledged) return false
        val previous = recent.remove(signal.contentKey)
        recent[signal.contentKey] = now
        while (recent.size > capacity) recent.remove(recent.keys.first())
        return previous == null || now - previous >= cooldownMs
    }
}
