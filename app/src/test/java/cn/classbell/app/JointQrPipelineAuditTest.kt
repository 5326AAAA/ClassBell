package cn.classbell.app

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class JointQrPipelineAuditTest {
    private val item=SignActivity("301","合成扫码",Course("101","201","合成课程"),otherId=2)
    private val notSigned get()=SignOutcome(false,"学习通确认未签到",SignResultCategory.NOT_SUBMITTED)
    private fun prefs(name:String):SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("joint-qr-audit-$name",0).apply{edit().clear().commit()}

    @Test fun anotherLedgerCannotUnlockARequestThatIsStillInFlight() {
        val prefs=prefs("shared-flight")
        val sender=SignLedger(prefs)
        val resultPage=SignLedger(prefs)
        try {
            assertTrue(sender.reserveSubmission("owner",item))
            assertFalse(resultPage.recoverUnsubmittedQr("owner",item,notSigned))
            assertTrue(resultPage.attempted("owner",item))
            assertFalse(resultPage.reserveSubmission("owner",item))
            sender.completeSubmission("owner",item,SignOutcome(false,"回复丢失",SignResultCategory.UNKNOWN))
            assertTrue(resultPage.recoverUnsubmittedQr("owner",item,notSigned))
            assertFalse(sender.attempted("owner",item))
            assertTrue(resultPage.reserveSubmission("owner",item))
        } finally {
            sender.finishSubmission("owner",item)
            prefs.edit().clear().commit()
        }
    }

    @Test fun simultaneousLedgerWrappersReserveTheSameAccountAndActivityOnlyOnce() {
        val prefs=prefs("one-winner")
        val pool=Executors.newFixedThreadPool(12)
        val start=CountDownLatch(1)
        val ledgers=List(12){SignLedger(prefs)}
        try {
            val pending=ledgers.map{ledger->pool.submit<Boolean>{start.await();ledger.reserveSubmission("owner",item)}}
            start.countDown()
            assertEquals(1,pending.count{it.get(5,TimeUnit.SECONDS)})
            assertTrue(ledgers.all{it.attempted("owner",item)})
            assertFalse(SignLedger(prefs).recoverUnsubmittedQr("owner",item,notSigned))
        } finally {
            start.countDown()
            ledgers.first().finishSubmission("owner",item)
            pool.shutdownNow()
            prefs.edit().clear().commit()
        }
    }

    @Test fun concurrentActivitiesOfOneAccountDoNotOverwriteEachOthersReservations() {
        val prefs=prefs("many-activities")
        val pool=Executors.newFixedThreadPool(10)
        val start=CountDownLatch(1)
        val activities=(301..310).map{item.copy(id=it.toString())}
        try {
            val pending=activities.map{activity->pool.submit<Boolean>{
                start.await()
                SignLedger(prefs).reserveSubmission("owner",activity)
            }}
            start.countDown()
            assertTrue(pending.all{it.get(5,TimeUnit.SECONDS)})
            val reader=SignLedger(prefs)
            assertTrue(activities.all{reader.attempted("owner",it)})
        } finally {
            start.countDown()
            val cleanup=SignLedger(prefs)
            activities.forEach{cleanup.finishSubmission("owner",it)}
            pool.shutdownNow()
            prefs.edit().clear().commit()
        }
    }

    @Test fun confirmedSuccessSurvivesLateNegativeQueriesAndRejectionsFromOtherScreens() {
        val prefs=prefs("success")
        val sender=SignLedger(prefs)
        val second=SignLedger(prefs)
        try {
            assertTrue(sender.reserveSubmission("owner",item))
            sender.completeSubmission("owner",item,SignOutcome(true,"签到成功"))
            assertFalse(second.recoverUnsubmittedQr("owner",item,notSigned))
            second.releaseRejected("owner",item)
            assertTrue(sender.attempted("owner",item))
            assertFalse(second.reserveSubmission("owner",item))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun independentPreferenceFilesDoNotShareSubmissionLocks() {
        val first=prefs("file-a")
        val second=prefs("file-b")
        val a=SignLedger(first)
        val b=SignLedger(second)
        try {
            assertTrue(a.reserveSubmission("owner",item))
            assertTrue(b.reserveSubmission("owner",item))
        } finally {
            a.finishSubmission("owner",item)
            b.finishSubmission("owner",item)
            first.edit().clear().commit()
            second.edit().clear().commit()
        }
    }

    @Test fun confirmedRecordStillBlocksDuplicatesIfTheLegacyAttemptSetIsMissing() {
        val prefs=prefs("confirmed-only")
        try {
            prefs.edit().putStringSet("sign.confirmed.owner",setOf(accountKey(item.key))).commit()
            val page=SignLedger(prefs)
            assertTrue(page.attempted("owner",item))
            assertFalse(page.reserveSubmission("owner",item))
            assertFalse(page.recoverUnsubmittedQr("owner",item,notSigned))
        } finally { prefs.edit().clear().commit() }
    }
}
