package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    @Test fun photoRequiredSignsKeepTheSignVoiceAndIgnoreCompletionMessages() {
        listOf("请完成拍照签到", "普通签到，需要上传照片").forEach {
            assertEquals(ReminderKind.SIGN_IN, NotificationRules.classify("课程", it))
        }
        assertNull(NotificationRules.classify("拍照签到", "签到成功"))
        assertNull(NotificationRules.classify("普通签到", "签到已结束"))
    }
    @Test fun combinedAndWeeklySignNotificationsUseTheSameRecognitionPath() {
        listOf("综合签到，请扫码并定位", "第8周签到", "签到需要定位和拍照").forEach { text ->
            assertTrue(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "课堂活动", text))
        }
        // The API can classify a custom title, but passive text recognition cannot invent its category.
        assertFalse(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "课程通知", "测试场地集合"))
    }
    @Test fun classroomExerciseAndSignNotificationsChooseDifferentVoices() {
        listOf("老师发起课堂活动", "新的随堂练习", "课堂测验请及时参加").forEach {
            assertEquals(ReminderKind.CLASSROOM, NotificationRules.classify("课程", it))
        }
        assertEquals(ReminderKind.SIGN_IN, NotificationRules.classify("课堂活动", "请及时扫码签到"))
    }
    @Test fun completedClassroomActivitiesAndSummariesCannotTrigger() {
        listOf("随堂练习已提交", "课堂活动已结束", "课堂测验已结束", "已完成课堂活动").forEach {
            assertNull(NotificationRules.classify("课程", it))
            assertFalse(NotificationRules.mayInspectUnclassified("课程", it))
        }
        assertNull(NotificationRules.classify("课堂活动", "签到成功"))
        assertNull(NotificationRules.classify("随堂练习", "开始", true))
    }
    @Test fun aTopicOnlyNotificationNeedsApiInspectionAndCannotDirectlySelectAVoice() {
        assertNull(NotificationRules.classify("离散数学", "主合取范式"))
        assertTrue(NotificationRules.mayInspectUnclassified("离散数学", "主合取范式"))
        assertFalse(NotificationRules.mayInspectUnclassified("", ""))
        assertFalse(NotificationRules.mayInspectUnclassified("随堂练习", "已提交"))
    }
    @Test fun ordinaryLocationQrAndPhotoAllMatchRegardlessOfNotificationTitle() {
        listOf("普通签到", "位置签到", "扫码签到", "拍照签到", "二维码签到", "手势签到").forEach { content ->
            assertTrue(content, NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "老师发起了课堂活动", content))
            assertFalse(content, NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "课堂活动", "$content 已完成签到"))
        }
    }
    private fun signal(source: String = "course-chat", text: String = "老师发起了签到", at: Long = 1000L) =
        SignSignal(source, "大学物理", text, at, at)

    @Test fun onlyTheExactLearningAppPackageCanTrigger() {
        assertTrue(NotificationRules.isCandidate("com.chaoxing.mobile", "大学物理", "请及时签到"))
        assertFalse(NotificationRules.isCandidate("com.example.chat", "大学物理", "请及时签到"))
        assertFalse(NotificationRules.isCandidate("com.chaoxing.mobile.fake", "大学物理", "请及时签到"))
    }
    @Test fun ordinaryNotificationsAndGroupSummariesAreIgnored() {
        assertFalse(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "通知", "有新作业"))
        assertFalse(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "通知", "签到", groupSummary = true))
    }
    @Test fun completedExpiredAndUnnecessarySignsDoNotTrigger() {
        listOf("签到成功", "你已签到", "已完成签到", "签到已结束", "签到已过期", "本次无需签到").forEach {
            assertFalse(it, NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "学习通", it))
        }
    }
    @Test fun deadlineAndNotYetSignedMessagesStillTrigger() {
        assertTrue(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "学习通", "尚未签到，截止时间 10:00"))
        assertTrue(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "签 到", "请及时完成"))
    }
    @Test fun anOldSignInInsideMessageHistoryDoesNotTriggerANewOrdinaryMessage() {
        val text = NotificationRules.latestText(listOf("请签到", "明天交作业"), emptyList(), "请签到\n明天交作业", "2 条消息")
        assertEquals("明天交作业", text)
        assertFalse(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "课程群", text))
    }
    @Test fun inboxUsesLatestLineAndPlainNotificationsUseTheirBody() {
        assertEquals("新签到", NotificationRules.latestText(emptyList(), listOf("旧通知", "新签到"), "历史", "摘要"))
        assertEquals("请签到", NotificationRules.latestText(emptyList(), emptyList(), "", "请签到"))
    }
    @Test fun eventsFromBeforeMonitoringAndAcknowledgedEventsAreIgnored() {
        val gate = NotificationGate()
        val item = signal()
        assertFalse(gate.accept(item, 0, 1001, emptySet()))
        assertFalse(gate.accept(item, 0, 900, setOf(item.key)))
        assertTrue(gate.accept(item, 0, 900, emptySet()))
    }
    @Test fun metadataRepostsAreCoalescedButALaterNewNotificationCanTrigger() {
        val gate = NotificationGate()
        assertTrue(gate.accept(signal(at = 1000), 0, 900, emptySet()))
        assertFalse(gate.accept(signal(at = 1001), 500, 900, emptySet()))
        assertTrue(gate.accept(signal(at = 1002), 31_000, 900, emptySet()))
    }
    @Test fun differentCourseNotificationsAreIndependent() {
        val gate = NotificationGate()
        assertTrue(gate.accept(signal("physics"), 0, 900, emptySet()))
        assertTrue(gate.accept(signal("math"), 10, 900, emptySet()))
    }
    @Test fun theDeduplicationCacheEvictsInsteadOfGrowingForever() {
        val gate = NotificationGate(capacity = 2)
        assertTrue(gate.accept(signal("one"), 0, 900, emptySet()))
        assertTrue(gate.accept(signal("two"), 1, 900, emptySet()))
        assertTrue(gate.accept(signal("three"), 2, 900, emptySet()))
        assertTrue(gate.accept(signal("one"), 3, 900, emptySet()))
    }
    @Test fun notificationModeDoesNotRequireAnAccountButQueriesDo() {
        assertTrue(NotificationRules.isCandidate(NotificationRules.SOURCE_PACKAGE, "课程", "签到开始"))
        assertFalse(NotificationRules.shouldQuery(true, false, 2))
        assertFalse(NotificationRules.shouldQuery(true, true, 0))
        assertFalse(NotificationRules.shouldQuery(false, true, 2))
        assertTrue(NotificationRules.shouldQuery(true, true, 2))
    }
    @Test fun upgradingWithoutAnExplicitPollingPreferenceKeepsPollingOff() {
        assertFalse(NotificationRules.DEFAULT_POLLING)
    }
    @Test fun notificationIdsChangeForNewContentAndNewEvents() {
        assertNotEquals(signal().key, signal(text = "另一条签到").key)
        assertNotEquals(signal().key, signal(at = 2000).key)
        assertEquals(signal().key, signal().key)
    }
}
