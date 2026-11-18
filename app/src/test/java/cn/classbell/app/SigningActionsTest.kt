package cn.classbell.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class SigningActionsTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","测试课程")
    private val sign=SignActivity("301","测试签到",course,otherId=0)
    private val submits=AtomicInteger()
    private val plans=AtomicInteger()
    private var identity=""
    @Before fun setup() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()
        identity=accountKey("sign-test")
        app.courseMemory.activate(app.prefs,identity,listOf(course))
    }
    @After fun cleanup() {app.prefs.edit().clear().commit();app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()}
    private fun enable() {app.prefs.edit().putBoolean("autoNormal.$identity",true).commit()}
    private fun actions(mode:SignMode=SignMode.NORMAL, onPlan:()->Unit={}):SigningActions = SigningActions(app) { guard ->
        object:SignSource {
            override fun activities(course:Course)=listOf(sign)
            override fun plan(activity:SignActivity):SignPlan {guard();plans.incrementAndGet();onPlan();return SignPlan(mode,"测试要求")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit();guard();submits.incrementAndGet();return SignOutcome(true,"签到成功")
            }
            override fun cancel() {}
        }
    }
    @Test fun defaultsToOffAndDoesNotCallAnyRemoteSource() {
        actions().autoSubmit(sign){true}
        assertEquals(0,plans.get());assertEquals(0,submits.get())
    }
    @Test fun repeatsAndProcessRecreationDoNotRepeatAnAutomaticAttempt() {
        enable();actions().autoSubmit(sign){true};actions().autoSubmit(sign){true}
        assertEquals(1,submits.get())
    }
    @Test fun photoAndUnknownRequirementsNeverReachAutomaticSubmission() {
        enable();actions(SignMode.PHOTO).autoSubmit(sign){true}
        actions(SignMode.OFFICIAL).autoSubmit(sign.copy(id="302")){true}
        assertEquals(0,submits.get())
    }
    @Test fun classroomRawUnknownSubtypeAndUnselectedCourseAreNotAutomaticallySubmitted() {
        enable()
        for(activity in listOf(sign.copy(kind=ReminderKind.CLASSROOM),sign.copy(otherId=null),sign.copy(course=Course("9","8","未选课程"))))
            actions().autoSubmit(activity){true}
        assertEquals(0,plans.get());assertEquals(0,submits.get())
    }
    @Test fun disablingOrStoppingDuringPreflightPreventsCommit() {
        enable();actions(onPlan={app.prefs.edit().putBoolean("autoNormal.$identity",false).commit()}).autoSubmit(sign){true}
        assertEquals(0,submits.get())
        enable();var running=true
        actions(onPlan={running=false}).autoSubmit(sign.copy(id="302")){running}
        assertEquals(0,submits.get())
    }
    @Test fun ignoringOneActivityStopsItsPreflightAndAlsoBlocksAnInFlightCommit() {
        enable()
        val ledger=app.signing.ledger
        ledger.setIgnored(identity,sign,true)
        assertFalse(ledger.ignored(accountKey("another-account"),sign.key))
        actions().autoSubmit(sign){true}
        assertEquals(0,plans.get())
        val other=sign.copy(id="302")
        actions(onPlan={ledger.setIgnored(identity,other,true)}).autoSubmit(other){true}
        assertEquals(1,plans.get())
        assertEquals(0,submits.get())
        ledger.setIgnored(identity,sign,false)
        actions().autoSubmit(sign){true}
        assertEquals(1,submits.get())
    }
    @Test fun accountSwitchDuringPreflightDoesNotBorrowTheOtherAccount() {
        enable();actions(onPlan={app.prefs.edit().putString("identity",accountKey("other")).commit()}).autoSubmit(sign){true}
        assertEquals(0,submits.get())
    }
    @Test fun concurrentReservationsAllowOnlyOneAutomaticAttempt() {
        val ledger=SignLedger(app.prefs);val start=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map {executor.submit<Boolean>{start.await();ledger.reserve(identity,sign)}}
            start.countDown()
            assertEquals(1,results.count {it.get(3,TimeUnit.SECONDS)})
        } finally {executor.shutdownNow()}
    }
    @Test fun accountChangeAtAdmissionCannotBorrowNewCookiesOrTheOldAccountsOptIn() {
        enable()
        actions().autoSubmit(sign) {
            // Same course remains selected, but the logged-in account changed before source creation.
            app.prefs.edit().putString("identity",accountKey("newly-logged-in-account")).commit()
            true
        }
        assertEquals(0,plans.get());assertEquals(0,submits.get())
    }
}
