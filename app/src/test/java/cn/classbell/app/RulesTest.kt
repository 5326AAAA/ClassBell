package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Reduced protocol shapes; live-observed fields use replaced IDs and timestamps. */
class RulesTest {
    @Test fun todayPhotoRequiredSamplesStillUseOrdinarySignCategoryAndLongIds() {
        // 2026-10-30 WEB/data-structures lists both said ordinary sign, subtype 0.
        // Photo display exists on the completed detail pages; the list has no photo flag.
        // All IDs/times below are replaced; ongoing status is synthetic.
        val web = Course("101", "201", "测试WEB实践")
        val data = Course("102", "202", "测试数据结构")
        val webBody = list("""{"id":5000000001001,"activeType":2,"type":2,"otherId":"0","source":15,
            "nameOne":"普通签到","status":1,"userStatus":0,"startTime":${now - 1000},"endTime":${now + 1_800_000}}""")
        val dataBody = list("""{"id":5000000001002,"activeType":2,"type":2,"otherId":"0","source":15,
            "nameOne":"普通签到","status":1,"userStatus":0,"startTime":${now - 1000},"endTime":${now + 180_000}}""")
        val found = ApiParser.activities(webBody, web, now) + ApiParser.activities(dataBody, data, now)
        assertEquals(listOf("5000000001001", "5000000001002"), found.map { it.id })
        assertEquals(setOf(web.key, data.key), found.map { it.course.key }.toSet())
        assertTrue(found.all { it.kind == ReminderKind.SIGN_IN })
        assertEquals(2, ReminderRules.unseen(found, emptySet(), emptySet()).size)
    }
    @Test fun completedOrExpiredPhotoSampleShapeCannotReplayAnOldAlert() {
        val items = """{"id":5000000001001,"activeType":2,"type":2,"otherId":"0","source":15,"nameOne":"普通签到",
            "status":2,"userStatus":1,"startTime":${now - 1_801_000},"endTime":${now - 1000}},
            {"id":5000000001002,"activeType":2,"type":2,"otherId":"0","source":15,"nameOne":"普通签到",
            "status":1,"userStatus":0,"startTime":${now - 181_000},"endTime":${now - 1000}}"""
        assertTrue(ApiParser.activities(list(items), course, now).isEmpty())
    }
    @Test fun liveObservedClassroomType42IncludesTopicOnlyTitles() {
        val body = """{"result":1,"data":{"activeList":[
            {"id":9401,"activeType":42,"type":42,"source":15,"nameOne":"主合取范式","status":1,"userStatus":0},
            {"id":9402,"activeType":42,"type":42,"source":15,"nameOne":"随堂练习","status":1,"userStatus":0}]}}"""
        val found = ApiParser.activities(body, course, now)
        assertEquals(listOf("主合取范式", "随堂练习"), found.map { it.title })
        assertTrue(found.all { it.kind == ReminderKind.CLASSROOM })
    }
    @Test fun endedOrExpiredClassroomActivitiesAndSurveysAreExcluded() {
        val items = """{"id":9401,"activeType":42,"status":2,"nameOne":"主合取范式"},
            {"id":9402,"activeType":42,"status":1,"endTime":1799999990000},
            {"id":9403,"activeType":14,"status":1,"nameOne":"Survey"},
            {"id":9404,"activeType":45,"status":1,"nameOne":"课堂通知"}"""
        assertTrue(ApiParser.activities(list(items), course, now).isEmpty())
    }
    @Test fun mixedApiResultsKeepReminderKindsAndExplicitCategoryPriority() {
        val items = """{"id":9401,"activeType":2,"otherId":"2","status":1,"nameOne":"签到"},
            {"id":9402,"type":42,"status":1,"nameOne":"课堂练习"},
            {"id":9403,"activeType":14,"type":42,"status":1,"nameOne":"Survey"}"""
        assertEquals(listOf(ReminderKind.SIGN_IN, ReminderKind.CLASSROOM), ApiParser.activities(list(items), course, now).map { it.kind })
    }
    private val course = Course("101", "201", "大学物理")
    private val now = 1_800_000_000_000L
    private fun list(items: String) = """{"result":1,"data":{"activeList":[$items]}}"""

    @Test fun courseUsesClassIdRatherThanChannelKeyAndDeduplicates() {
        val body = """{"result":1,"channelList":[
            {"key":"wrong","content":{"id":201,"course":{"data":[{"id":101,"name":"大学物理"}]}}},
            {"content":{"id":201,"course":{"data":[{"id":101,"name":"大学物理"}]}}}
        ]}"""
        assertEquals(listOf(course), ApiParser.courses(body))
    }
    @Test fun emptyCourseListIsValidButUnknownSchemaFails() {
        assertTrue(ApiParser.courses("""{"result":1,"channelList":[]}""").isEmpty())
        assertThrows(ApiProblem::class.java) { ApiParser.courses("""{"result":1,"data":[]}""") }
    }
    @Test fun onlyExplicitOngoingSignActivitiesTrigger() {
        val items = """{"id":1,"activeType":2,"type":2,"otherId":"0","status":1,"nameOne":"普通签到"},
            {"id":2,"activeType":2,"otherId":"0","status":2},
            {"id":3,"activeType":4,"type":4,"otherId":2,"status":1,"nameOne":"签到测试问卷"},
            {"id":4,"otherId":2,"nameOne":"签到"},
            {"activeType":2,"status":1}"""
        assertEquals(listOf("1"), ApiParser.activities(list(items), course, now).map { it.id })
    }
    @Test fun respectsSecondsAndMillisecondsAndUnscheduledEnd() {
        val items = """{"id":1,"activeType":2,"otherId":"0","status":1,"startTime":1799999990,"endTime":1800000010},
            {"id":2,"activeType":2,"otherId":"0","status":1,"endTime":1799999999000},
            {"id":3,"activeType":2,"otherId":"0","status":1,"startTime":1800000001},
            {"id":4,"activeType":2,"otherId":"0","status":1,"endTime":0}"""
        assertEquals(listOf("1", "4"), ApiParser.activities(list(items), course, now).map { it.id })
    }
    @Test fun duplicateUpdatesDoNotCreateMultipleAlerts() {
        val activity = SignActivity("1", "签到", course)
        assertEquals(listOf(activity), ReminderRules.unseen(listOf(activity, activity), emptySet(), emptySet()))
        assertTrue(ReminderRules.unseen(listOf(activity), setOf(activity.key), emptySet()).isEmpty())
        assertTrue(ReminderRules.unseen(listOf(activity), emptySet(), setOf(activity.key)).isEmpty())
    }
    @Test fun sameActivityIdInDifferentCoursesIsDistinct() {
        val first = SignActivity("1", "签到", course)
        val second = SignActivity("1", "签到", Course("102", "202", "高等数学"))
        assertEquals(listOf(second), ReminderRules.unseen(listOf(first, second), setOf(first.key), emptySet()))
    }
    @Test fun unknownActivitySchemaIsNotReportedAsNoSignIn() {
        assertThrows(ApiProblem::class.java) { ApiParser.activities("""{"result":1,"data":{}}""", course, now) }
        assertTrue(ApiParser.activities(list(""), course, now).isEmpty())
    }
    @Test fun loginRedirectPagesAndMessagesAreDetected() {
        assertThrows(LoginRequired::class.java) { ApiParser.json("<html>用户登录 passport2.chaoxing.com</html>") }
        assertThrows(LoginRequired::class.java) { ApiParser.courses("""{"result":0,"msg":"请先登录"}""") }
    }
    @Test fun badJsonIsAnError() {
        assertThrows(ApiProblem::class.java) { ApiParser.json("not json") }
    }
    @Test fun credentialsCannotGoToHttpOrLookalikeDomains() {
        assertTrue(ChaoxingProtocol.allowed("https://passport2.chaoxing.com/fanyalogin".toHttpUrl()))
        assertFalse(ChaoxingProtocol.allowed("http://passport2.chaoxing.com/fanyalogin".toHttpUrl()))
        assertFalse(ChaoxingProtocol.allowed("https://passport2.chaoxing.com.attacker.invalid/".toHttpUrl()))
        assertFalse(ChaoxingProtocol.allowed("https://example.com/".toHttpUrl()))
    }
    @Test fun retriesBackOffAndAreBounded() {
        assertEquals(30L, ReminderRules.retryDelaySeconds(0, 30))
        assertEquals(60L, ReminderRules.retryDelaySeconds(1, 30))
        assertEquals(300L, ReminderRules.retryDelaySeconds(200, 30))
    }
    @Test fun differentAccountsHaveDifferentReminderNamespaces() {
        assertNotEquals(accountKey("example-a"), accountKey("example-b"))
        assertEquals(64, accountKey("example-a").length)
    }
    @Test fun ordinarySignInFromLiveObservedFieldsIsNotMissedWhenOngoing() {
        // Same fields as an observed ordinary sign-in; IDs and times are synthetic.
        val item = """{"userStatus":0,"nameTwo":"","otherId":"0","source":15,"isLook":1,
            "type":2,"activeType":2,"nameOne":"普通签到","startTime":1799999990000,
            "id":9001,"endTime":1800000010000,"status":1}"""
        assertEquals(listOf("9001"), ApiParser.activities(list(item), course, now).map { it.id })
    }
    @Test fun liveObservedEndedOrdinarySignDoesNotTrigger() {
        val item = """{"userStatus":1,"otherId":"0","source":15,"type":2,"activeType":2,
            "nameOne":"普通签到","startTime":1799999980000,"id":9001,"endTime":1799999990000,"status":2}"""
        assertTrue(ApiParser.activities(list(item), course, now).isEmpty())
    }
    @Test fun signSubtypeCannotOverrideAnExplicitNonSignCategory() {
        val items = """{"id":1,"activeType":4,"type":2,"otherId":2,"status":1},
            {"id":2,"otherId":2,"status":1,"nameOne":"签到"}"""
        assertTrue(ApiParser.activities(list(items), course, now).isEmpty())
    }
    @Test fun categoryTypeIsUsedWhenActiveTypeIsAbsent() {
        val items = """{"id":1,"type":2,"otherId":"0","status":1},
            {"id":2,"type":4,"otherId":2,"status":1}"""
        assertEquals(listOf("1"), ApiParser.activities(list(items), course, now).map { it.id })
    }
    @Test fun allSignSubtypesAndCustomTitlesAreKept() {
        // Synthetic fixtures: these are NOT claimed to be live-verified subtype mappings.
        val titles = listOf("普通签到", "位置签到", "扫码签到", "拍照签到", "自定义课堂考勤")
        val items = titles.mapIndexed { index, title ->
            """{"id":${index + 1},"activeType":2,"otherId":"$index","status":1,"nameOne":"$title"}"""
        }.joinToString(",")
        assertEquals(titles, ApiParser.activities(list(items), course, now).map { it.title })
    }
    @Test fun liveObservedQrSignShapeTriggersOnlyWhenOngoing() {
        // Linear algebra sample: observed category/subtype/title/status, replaced ID and times.
        val ended = """{"id":9101,"activeType":2,"type":2,"otherId":"2","nameOne":"二维码签到",
            "status":2,"userStatus":1,"startTime":1799999980000,"endTime":1799999990000}"""
        assertTrue(ApiParser.activities(list(ended), course, now).isEmpty())
        val ongoing = """{"id":9102,"activeType":2,"type":2,"otherId":"2","nameOne":"二维码签到",
            "status":1,"userStatus":0,"startTime":1799999990000,"endTime":1800000010000}"""
        assertEquals(listOf("二维码签到"), ApiParser.activities(list(ongoing), course, now).map { it.title })
    }
    @Test fun liveObservedLocationSignShapeRespectsStatusAndExpiry() {
        // PE(2): observed category 2, subtype 4, source 15; IDs/times/status variants are synthetic.
        val shape = """"activeType":2,"type":2,"otherId":"4","source":15,"nameOne":"位置签到""""
        val items = """{"id":9201,$shape,"status":1,"userStatus":0,"startTime":1799999990000,"endTime":1800000010000},
            {"id":9202,$shape,"status":2,"userStatus":1},
            {"id":9203,$shape,"status":1,"userStatus":0,"endTime":1799999990000}"""
        assertEquals(listOf("9201"), ApiParser.activities(list(items), course, now).map { it.id })
    }
    @Test fun liveObservedPeCustomTitleDoesNotNeedTheSignKeywordInApiMode() {
        // An observed PE activity had category 2/subtype 0 with a meeting-location title.
        val item = """{"id":9204,"activeType":2,"type":2,"otherId":"0","source":15,
            "nameOne":"测试场地集合","status":1,"userStatus":0}"""
        assertEquals(listOf("测试场地集合"), ApiParser.activities(list(item), course, now).map { it.title })
    }
    @Test fun englishWeeklySignWithCombinedRequirementsStillUsesTheSignCategory() {
        // English list shows subtype 2; completed detail also displays a location result.
        // Extra requirement fields below are synthetic: parsing must not depend on their names.
        val item = """{"id":9301,"activeType":2,"type":2,"otherId":"2","source":15,
            "nameOne":"第8周签到","status":1,"userStatus":0,"startTime":1799999990000,
            "endTime":1800000010000,"extraInfo":{"syntheticQr":true,"syntheticLocation":true,"syntheticPhoto":true}}"""
        assertEquals(listOf("第8周签到"), ApiParser.activities(list(item), course, now).map { it.title })
        assertTrue(ApiParser.activities(list(item.replace("\"status\":1", "\"status\":2")), course, now).isEmpty())
    }
}
