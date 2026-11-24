package cn.classbell.app

import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/** Local exchange fixtures distinguish preparation failures from uncertain final submissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class QrPrecommitRecoveryTest {
    private val item=SignActivity("301","动态二维码恢复用例",Course("101","201","合成课程"),otherId=2)
    private fun qr(enc:String="FACDD34DBC291EE176")=checkNotNull(SignQr.parse(
        "https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=$enc"))
    private fun ledger(name:String)=SignLedger(RuntimeEnvironment.getApplication()
        .getSharedPreferences("qr-precommit-recovery-$name",0).apply{edit().clear().commit()})
    private fun fixture(request:Request):String=when(request.url.encodedPath) {
        "/v2/apis/active/student/activelist"->"""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":2,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo"->JSONObject().put("result",1).put("data",JSONObject()
            .put("id",301).put("courseId",101).put("clazzid",201).put("activeType",2).put("status",1)
            .put("otherId",2).put("ifphoto",0).put("configJson","{}")
            .put("ifNeedVCode",0).put("showVCode",0).put("ifopenAddress",0)
            .put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0).put("openPreventCheatFlag",0)
            .put("openSignOutFlag",0).put("ifrefreshewm",1).put("ewmRefreshTime",5)).toString()
        "/apis/login/userLogin4Uname.do"->"""{"msg":{"uid":"901","name":"合成学生","fid":"101"}}"""
        "/newsign/preSign"->"<html>准备页面</html>"
        "/pptSign/stuSignajax"->"success"
        else->throw AssertionError("Unexpected fixture path ${request.url.encodedPath}")
    }
    private fun api(exchange:(Request)->String)=SigningApi(listOf(Cookie.Builder().name("_uid").value("901")
        .domain("chaoxing.com").path("/").secure().build()),{},allowQr=true,exchange=exchange)
    private fun submit(ledger:SignLedger,api:SignSource,prepared:PreparedQr?,material:SignQr=qr())=
        SignSubmission(ledger).submit("owner",item,api,
            SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared)){}

    @Test fun interruptedQrBoundPreparationAllowsFreshScanWithoutReservingOrSending() {
        listOf(IOException("synthetic disconnect"),SocketTimeoutException("synthetic timeout")).forEachIndexed{index,failure->
            var boundPreparations=0
            var finalSends=0
            val ledger=ledger("preparation-$index")
            val api=api{request->
                if(request.url.encodedPath=="/newsign/preSign" && request.url.queryParameter("rcode")!=null) {
                    boundPreparations++
                    if(boundPreparations==1)throw failure
                }
                if(request.url.encodedPath=="/pptSign/stuSignajax")finalSends++
                fixture(request)
            }
            try {
                val prepared=api.prepareQr(item)
                val interrupted=submit(ledger,api,prepared)
                assertEquals(SignResultCategory.NOT_SUBMITTED,interrupted.category)
                assertTrue(interrupted.retryAllowed)
                assertFalse(ledger.attempted("owner",item))
                assertEquals(0,finalSends)
                val retried=submit(ledger,api,prepared,qr("ABCDEF0123456789"))
                assertTrue(retried.successful)
                assertEquals(2,boundPreparations)
                assertEquals(1,finalSends)
                assertTrue(ledger.attempted("owner",item))
            } finally {api.cancel()}
        }
    }

    @Test fun disconnectedFinalSubmissionRemainsUncertainAndCannotBeSentAgain() {
        var finalSends=0
        val ledger=ledger("final-reply-loss")
        val api=api{request->
            if(request.url.encodedPath=="/pptSign/stuSignajax") {
                finalSends++
                throw IOException("synthetic final response loss")
            }
            fixture(request)
        }
        try {
            val prepared=api.prepareQr(item)
            val uncertain=submit(ledger,api,prepared)
            assertEquals(SignResultCategory.UNKNOWN,uncertain.category)
            assertFalse(uncertain.retryAllowed)
            assertTrue(ledger.attempted("owner",item))
            assertEquals(SignResultCategory.ALREADY_ATTEMPTED,
                submit(ledger,api,prepared,qr("ABCDEF0123456789")).category)
            assertEquals(1,finalSends)
        } finally {api.cancel()}
    }

    @Test fun qrBoundLoginAndOfficialVerificationResponsesDoNotEnableAutomaticRetry() {
        listOf("<html>用户登录</html>","<html>请进行人脸识别</html>").forEachIndexed{index,page->
            var finalSends=0
            val ledger=ledger("official-requirement-$index")
            val api=api{request->
                if(request.url.encodedPath=="/pptSign/stuSignajax")finalSends++
                if(request.url.encodedPath=="/newsign/preSign" && request.url.queryParameter("rcode")!=null)page
                else fixture(request)
            }
            try {
                val prepared=api.prepareQr(item)
                val rejected=submit(ledger,api,prepared)
                assertEquals(SignResultCategory.NOT_SUBMITTED,rejected.category)
                assertFalse(rejected.retryAllowed)
                assertFalse(ledger.attempted("owner",item))
                assertEquals(0,finalSends)
            } finally {api.cancel()}
        }
    }

    @Test fun unpreparedTransportFailuresDoNotAcquireThePreparedRecoveryPolicy() {
        val ledger=ledger("unprepared")
        val source=object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.QR,"扫码")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome=throw AssertionError()
            override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome=
                throw IOException("synthetic unprepared transport failure")
            override fun cancel(){}
        }
        val result=submit(ledger,source,null)
        assertEquals(SignResultCategory.NOT_SUBMITTED,result.category)
        assertFalse(result.retryAllowed)
        assertFalse(ledger.attempted("owner",item))
    }
}
