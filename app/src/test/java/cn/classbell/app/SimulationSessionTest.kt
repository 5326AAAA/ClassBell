package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class SimulationSessionTest {
    @Test fun newSessionHasNoInventedSuccessfulSteps() {
        val session = SimulationSession()
        session.begin(1000, 0, "one")
        val p = session.snapshot()!!
        assertFalse(p.sent); assertFalse(p.received); assertFalse(p.recognized); assertFalse(p.alerted)
    }
    @Test fun wrongAndExpiredTokensAreNotAccepted() {
        val session = SimulationSession()
        session.begin(1000, 0, "one")
        assertFalse(session.receive("wrong", 2000))
        assertFalse(session.receive("one", 61_001))
    }
    @Test fun oneNotificationIsReceivedAtMostOnce() {
        val session = SimulationSession()
        session.begin(1000, 0, "one")
        assertTrue(session.receive("one", 2000))
        assertFalse(session.receive("one", 2001))
    }
    @Test fun recognitionAndAlertCannotJumpOverTheListenerStep() {
        val session = SimulationSession()
        session.begin(1000, 0, "one")
        session.recognize("one", 2000); session.alert("one", 2000)
        assertFalse(session.snapshot()!!.recognized); assertFalse(session.snapshot()!!.alerted)
    }
    @Test fun completeProgressIsPreservedAfterTheUserStops() {
        val session = SimulationSession()
        session.begin(1000, 0, "one")
        session.posted("one", 2000); session.receive("one", 2000)
        session.recognize("one", 2000); session.alert("one", 2000)
        session.finish("stopped")
        assertTrue(session.snapshot()!!.alerted); assertTrue(session.snapshot()!!.finished)
        assertFalse(session.active("one", 2001))
    }
    @Test fun newSessionInvalidatesAnOlderPendingNotification() {
        val session = SimulationSession()
        session.begin(1000, 0, "one"); session.begin(2000, 10_000, "two")
        assertFalse(session.receive("one", 3000)); assertTrue(session.receive("two", 3000))
    }
    @Test fun ordinaryOwnAppNotificationsStillAreNotLearningAppNotifications() {
        assertFalse(NotificationRules.isCandidate("cn.classbell.app", "签到", "签到已发起"))
        assertTrue(NotificationRules.matchesContent("【本地测试】模拟课堂签到", "模拟签到已发起"))
    }
    @Test fun delayIsBounded() {
        assertThrows(IllegalArgumentException::class.java) { SimulationSession().begin(0, 10_001) }
    }
}
