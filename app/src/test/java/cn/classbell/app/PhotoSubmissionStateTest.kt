package cn.classbell.app

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class PhotoSubmissionStateTest {
    @Test fun concurrentReservationAfterUploadRemainsAlreadyAttempted() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        val prefs=app.getSharedPreferences("photo-duplicate-fixture",0)
        prefs.edit().clear().commit()
        val ledger=SignLedger(prefs)
        val item=SignActivity("301","合成拍照签到",Course("101","201","合成课程"),otherId=0)
        val file=File.createTempFile("synthetic-race-",".jpg",app.cacheDir)
        val source=object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.PHOTO,"普通拍照")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                assertTrue(ledger.reserve("fixture",item))
                try {beforeCommit()}catch(e:Exception){throw PhotoTransferException(PhotoTransferState.CONFIRMED,e)}
                error("Duplicate submission must not reach commit")
            }
            override fun cancel(){}
        }
        try {
            val result=SignSubmission(ledger).submit("fixture",item,source,SignInputs(photo=file)){}
            assertEquals(SignResultCategory.ALREADY_ATTEMPTED,result.category)
            assertEquals(PhotoTransferState.CONFIRMED,result.photoState)
            assertTrue(ledger.attempted("fixture",item));assertFalse(result.successful)
        }finally{file.delete();prefs.edit().clear().commit()}
    }
    @Test fun uploadAndAttendanceOutcomesStaySeparateAcrossFailuresAndReservations() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        val prefs=app.getSharedPreferences("photo-state-fixture",0)
        val item=SignActivity("301","合成拍照签到",Course("101","201","合成课程"),otherId=0)
        val file=File.createTempFile("synthetic-state-",".jpg",app.cacheDir).apply{writeBytes(byteArrayOf(1))}
        try {
            for((state,committed) in listOf(PhotoTransferState.UNCONFIRMED to false,PhotoTransferState.CONFIRMED to false,PhotoTransferState.CONFIRMED to true)) {
                prefs.edit().clear().commit();val ledger=SignLedger(prefs)
                val source=object:SignSource {
                    override fun activities(course:Course)=listOf(item)
                    override fun plan(activity:SignActivity)=SignPlan(SignMode.PHOTO,"普通拍照")
                    override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                        if(committed)beforeCommit()
                        throw PhotoTransferException(state,IOException("private response must not appear"))
                    }
                    override fun cancel(){}
                }
                val result=SignSubmission(ledger).submit("fixture",item,source,SignInputs(photo=file)){}
                assertEquals(state,result.photoState)
                assertEquals(if(committed)SignResultCategory.UNKNOWN else SignResultCategory.NOT_SUBMITTED,result.category)
                assertEquals(committed,ledger.attempted("fixture",item))
                assertFalse(result.message.contains("private response"));assertFalse(result.successful)
            }
        } finally {file.delete();prefs.edit().clear().commit()}
    }
}
