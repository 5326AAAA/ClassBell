package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class PhotoRecoveryTest {
    private val item=SignActivity("301","合成拍照",Course("101","201","合成课程"),otherId=0)
    private val notSigned=SignOutcome(false,"未签到",SignResultCategory.NOT_SUBMITTED)
    private fun ledger()=SignLedger(RuntimeEnvironment.getApplication().getSharedPreferences("photo-recovery-${System.nanoTime()}",0))
    @Test fun confirmedNotSignedUnlocksOnlyThisAccountsPhotoAttempt() {
        val ledger=ledger()
        assertTrue(ledger.reserveSubmission("a",item));ledger.finishSubmission("a",item)
        assertTrue(ledger.reserveSubmission("b",item));ledger.finishSubmission("b",item)
        assertFalse(ledger.recoverUnsubmittedPhoto("a",item,SignOutcome(false,"未知",SignResultCategory.UNKNOWN)))
        assertTrue(ledger.attempted("a",item))
        assertTrue(ledger.recoverUnsubmittedPhoto("a",item,notSigned))
        assertFalse(ledger.attempted("a",item));assertTrue(ledger.attempted("b",item))
    }
    @Test fun inFlightAndConfirmedSuccessCannotBeReleasedByStaleQuery() {
        val ledger=ledger();assertTrue(ledger.reserveSubmission("a",item))
        assertFalse(ledger.recoverUnsubmittedPhoto("a",item,notSigned))
        ledger.completeSubmission("a",item,SignOutcome(true,"成功"))
        assertFalse(ledger.recoverUnsubmittedPhoto("a",item,notSigned));assertTrue(ledger.attempted("a",item))
    }
    @Test fun photoRecoveryRejectsOtherTypesAndBlankOwners() {
        val ledger=ledger()
        assertFalse(ledger.recoverUnsubmittedPhoto("a",item.copy(otherId=2),notSigned))
        assertFalse(ledger.recoverUnsubmittedPhoto("",item,notSigned))
    }
    @Test fun photoRejectionIsActionableAndVerificationRetainsPriority() {
        for(reply in listOf("请先上传照片","照片不能为空","图片上传失败","{\"result\":false,\"msg\":\"请上传照片\"}")) {
            val result=SignProtocol.outcome(reply)
            assertEquals(reply,SignResultCategory.FAILED,result.category);assertTrue(result.retryAllowed)
        }
        assertEquals(SignResultCategory.VERIFICATION,SignProtocol.outcome("请上传照片进行人脸验证").category)
        assertEquals(SignResultCategory.UNKNOWN,SignProtocol.outcome("<html>请上传照片</html>").category)
        assertTrue(SignProtocol.outcome("\uFEFFsuccess").successful)
    }
}
