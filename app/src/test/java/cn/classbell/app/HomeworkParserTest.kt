package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class HomeworkParserTest {
    private val now = 1_800_000_000_000L
    private val course = Course("101", "201", "数据结构与算法")
    private fun row(id: String, status: String, time: String = "", review: Boolean = false, statusClass: Boolean = false): String {
        val url = if (review) "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/eval-list?courseId=101&classId=201&workRelationId=$id"
            else "https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial?courseId=101&clazzId=201&taskrefId=$id&type=work"
        return """<li data="$url"><div role="option"><p>测试作业$id</p><span ${if (statusClass) "class=\"status\"" else ""}>$status</span><span>《课程简称…》</span><span class="fr">$time</span><span aria-label="无可见文字"></span></div></li>"""
    }
    @Test fun readsMobilePendingPlainSpanSubmittedAndPeerReviewSeparately() {
        val html = row("1", "未提交", "剩余227小时40分钟", statusClass = true) + row("2", "待批阅", "剩余1小时") +
            row("3", "已完成") + row("3", "已互评", review = true)
        val snapshot = HomeworkParser.parse("<ul>$html</ul>", listOf(course), now)
        assertEquals(4, snapshot.items.size)
        assertEquals(course.name, snapshot.items.first().courseName)
        assertEquals(HomeworkState.PENDING, snapshot.items[0].state(now))
        assertEquals(HomeworkState.SUBMITTED, snapshot.items[1].state(now + 7_200_000))
        assertEquals(1, snapshot.items.count { it.peerReview })
        assertEquals(4, snapshot.items.map { it.key }.toSet().size)
        assertEquals(now + (227 * 60L + 40) * 60_000, snapshot.items.first().dueAt)
        assertTrue(snapshot.items.first().remaining(now).startsWith("约剩 9天 11小时"))
    }
    @Test fun duplicateRowsDoNotDuplicateSameHomework() {
        assertEquals(1, HomeworkParser.parse(row("1", "未提交") + row("1", "未提交"), emptyList(), now).items.size)
    }
    @Test fun unknownDeadlineRemainsPendingAndUnknownStatusIsNeverGuessedOverdue() {
        val pending = HomeworkParser.parse(row("1", "未提交"), emptyList(), now).items.single()
        assertEquals(HomeworkState.PENDING, pending.state(now + 365L * 86400_000))
        assertEquals("截止时间未读取，请查看作业详情核对", pending.remaining(now))
        val unknown = HomeworkParser.parse(row("2", "新状态", "剩余1分钟"), emptyList(), now).items.single()
        assertEquals(HomeworkState.UNKNOWN, unknown.state(now + 120_000))
    }
    @Test fun elapsedDeadlineOnlyExpiresUnsubmittedWork() {
        val pending = HomeworkParser.parse(row("1", "未提交", "剩余1分钟"), emptyList(), now).items.single()
        assertEquals(HomeworkState.PENDING, pending.state(now + 59_999))
        assertEquals(HomeworkState.EXPIRED, pending.state(now + 60_000))
        val completed = pending.copy(sourceStatus = "已完成", sourceTime = "已截止")
        assertEquals(HomeworkState.SUBMITTED, completed.state(now + 120_000))
        assertTrue(pending.remaining(now + 120_000).contains("补交"))
    }
    @Test fun supportsDaysAndRejectsUnboundedCountdownValues() {
        assertEquals(93_784_000L, HomeworkParser.remainingMillis("剩余1天2小时3分钟4秒"))
        assertNull(HomeworkParser.remainingMillis("剩余时间未知"))
        assertNull(HomeworkParser.remainingMillis("剩余999999999999999999999天"))
        assertEquals(0L, HomeworkParser.remainingMillis("剩余0分钟"))
    }
    @Test fun snapshotRoundTripsAndPreservesKindAndLink() {
        val snapshot = HomeworkParser.parse(row("1", "未提交", "剩余1小时") + row("1", "已互评", review = true), listOf(course), now)
        assertEquals(snapshot, HomeworkSnapshot.fromJson(snapshot.json()))
    }
    @Test fun onlyRecognizedEmptyPageMayClearPreviousRecords() {
        assertTrue(HomeworkParser.parse("<p>暂无作业</p>", emptyList(), now).items.isEmpty())
        assertThrows(ApiProblem::class.java) { HomeworkParser.parse("<div>网络异常</div>", emptyList(), now) }
        assertThrows(ApiProblem::class.java) { HomeworkParser.parse(row("1", ""), emptyList(), now) }
        assertThrows(LoginRequired::class.java) { HomeworkParser.parse("<input type=password>", emptyList(), now) }
    }
    @Test fun unknownDestinationsAndMalformedRecordsAreNotCachedAsEmptySuccess() {
        val valid = row("1", "未提交")
        assertThrows(ApiProblem::class.java) { HomeworkParser.parse(valid.replace("mooc1-api.chaoxing.com", "example.invalid"), emptyList(), now) }
        assertThrows(ApiProblem::class.java) { HomeworkParser.parse(valid.replace("taskrefId=1", "unknown=1"), emptyList(), now) }
        assertFalse(HomeworkParser.safeUrl("http://mooc1-api.chaoxing.com/work/stu-work"))
        assertFalse(HomeworkParser.safeUrl("https://mooc1-api.chaoxing.com:9443/work/stu-work"))
        assertFalse(HomeworkParser.safeUrl("https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/delete"))
        assertFalse(HomeworkParser.safeUrl("javascript:alert(1)"))
    }
}
