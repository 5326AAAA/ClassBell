package cn.classbell.app

import android.os.SystemClock
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class NativeMaterialSignTest {
    private val item=SignActivity("301","合成扫码签到",Course("101","201","合成课程"),otherId=2)
    private val qr=SignQr.parse("https://mobilelearn.chaoxing.com/widget/sign/e?id=301&enc=FACDD34DBC291EE176")!!
    private fun detail(type:Int=2,location:Boolean=false,verification:Boolean=false)=JSONObject().put("result",1).put("data",JSONObject()
        .put("id",301).put("courseId",101).put("clazzid",201).put("activeType",2).put("status",1).put("otherId",type).put("ifphoto",0)
        .put("configJson","{}").put("ifNeedVCode",if(verification)1 else 0).put("showVCode",0).put("ifopenAddress",if(location)1 else 0)
        .put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0).put("openPreventCheatFlag",0).put("openSignOutFlag",0)).toString()
    private fun response(request:Request,type:Int=2,uid:String="901",location:Boolean=false):String=when(request.url.encodedPath) {
        "/v2/apis/active/student/activelist"->"""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":$type,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo"->detail(type,location)
        "/apis/login/userLogin4Uname.do"->"""{"msg":{"uid":"$uid","name":"合成学生","fid":"101"}}"""
        "/newsign/preSign"->"<html>准备页面</html>"
        "/widget/sign/pcStuSignController/checkSignCode"->"""{"result":1}"""
        "/pptSign/stuSignajax"->"success"
        else->throw AssertionError("Unexpected fixture request")
    }
    private fun api(uid:String="901",allowLocation:Boolean=false,exchange:(Request)->String)=SigningApi(listOf(Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build()),{},allowLocation=allowLocation,allowQr=true,allowGesture=true,exchange=exchange)
    @Test fun parserAcceptsCurrentAndLegacyPayloadsWithoutRequiringCOrFixedLength() {
        assertEquals("301",qr.activityId)
        assertEquals("301",SignQr.parse("SIGNIN:aid=301&enc=FACDD34DBC291EE176")!!.activityId)
        assertEquals("SignQr(redacted)",qr.toString())
        listOf("&aid=301","&id=301","&enc=12345678","&cookie=secret").forEach{assertNull(SignQr.parse(qr.officialUrl+it))}
        assertNull(SignQr.parse(qr.officialUrl.replace("FACDD34DBC291EE176","not-a-hex-token")))
        assertThrows(ApiProblem::class.java){qr.requireFor(item.copy(id="302"))}
    }
    @Test fun newModesRequireManualEnablementAndStillHonorExtraVerification() {
        assertEquals(SignMode.OFFICIAL,SignProtocol.detail(detail(),item).mode)
        assertEquals(SignMode.QR,SignProtocol.detail(detail(),item,allowQr=true).mode)
        assertEquals(SignMode.GESTURE,SignProtocol.detail(detail(3),item,allowGesture=true).mode)
        assertEquals("LOCATION_DISABLED",SignProtocol.detail(detail(location=true),item,allowQr=true).issueCode)
        assertTrue(SignProtocol.detail(detail(location=true),item,allowLocation=true,allowQr=true).requiresLocation)
        assertEquals(SignMode.OFFICIAL,SignProtocol.detail(detail(verification=true),item,allowQr=true).mode)
    }
    @Test fun twoAccountsSubmitSameMaterialWithTheirOwnVerifiedIdentity() {
        val final=mutableListOf<Request>()
        for(uid in listOf("901","902")) {
            val api=api(uid){request->if(request.url.encodedPath.endsWith("stuSignajax"))final+=request;response(request,uid=uid)}
            try{assertTrue(api.submitQr(item,qr).successful)}finally{api.cancel()}
        }
        assertEquals(listOf("901","902"),final.map{it.url.queryParameter("uid")})
        final.forEach{request->
            assertEquals(qr.enc,request.url.queryParameter("enc"));assertEquals("301",request.url.queryParameter("activeId"))
            assertEquals("101",request.url.queryParameter("fid"));assertNull(request.url.queryParameter("validate"))
            assertNull(request.url.queryParameter("location"));assertNull(request.url.queryParameter("deviceId"))
        }
    }
    @Test fun wrongQrAndChangedRequirementsNeverCommit() {
        var sends=0
        val api=api{r->if(r.url.encodedPath.endsWith("stuSignajax"))sends++;if(r.url.encodedPath.endsWith("getPPTActiveInfo"))detail(verification=true) else response(r)}
        try {
            assertThrows(ApiProblem::class.java){api.submitQr(item.copy(id="302"),qr)}
            assertThrows(ApiProblem::class.java){api.submitQr(item,qr)}
            assertEquals(0,sends)
        }finally{api.cancel()}
    }
    @Test fun qrLocationRequiresFreshPhoneFixAndUsesLocationJsonOnlyWhenRequired() {
        val final=mutableListOf<Request>();val api=api(allowLocation=true){r->if(r.url.encodedPath.endsWith("stuSignajax"))final+=r;response(r,location=true)}
        try {
            assertThrows(ApiProblem::class.java){api.submitQr(item,qr)}
            assertTrue(final.isEmpty())
            assertTrue(api.submitQr(item,qr,LocationFix(1.35,103.82,10f,SystemClock.elapsedRealtime())).successful)
            val data=JSONObject(final.single().url.queryParameter("location")!!)
            assertEquals(1.35,data.getDouble("latitude"),0.001);assertEquals(103.82,data.getDouble("longitude"),0.001)
        }finally{api.cancel()}
    }
    @Test fun gestureAllowsThreeDigitPathChecksBeforeCommitAndNeverGuessesIt() {
        val seen=mutableListOf<Request>();val api=api{r->seen+=r;response(r,type=3)}
        try {
            assertTrue(api.submitGesture(item,"258").successful)
            val check=seen.indexOfFirst{it.url.encodedPath.endsWith("checkSignCode")}
            val commit=seen.indexOfFirst{it.url.encodedPath.endsWith("stuSignajax")}
            assertTrue(check in 0 until commit)
            assertEquals("258",seen[commit].url.queryParameter("signCode"))
            assertThrows(ApiProblem::class.java){api.submitGesture(item,"225")}
        }finally{api.cancel()}
    }
    @Test fun gestureSelectionPreservesDirectionAndIncludesCrossedMiddlePoint() {
        assertEquals("14789","179".fold(""){path,ch->GesturePath.append(path,ch)})
        assertEquals("258",GesturePath.append("2",'8'))
        assertEquals("159",GesturePath.append("1",'9'))
        assertEquals("258",GesturePath.append("258",'2'))
        listOf("258","14789","12","987654321").forEach{assertTrue(GesturePath.valid(it))}
        listOf("","2","2258","012","1234567891","abc").forEach{assertFalse(GesturePath.valid(it))}
    }
    @Test fun explicitRejectionAllowsManualRetryButLostReplyAndSuccessStayReserved() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("qr-retry",0);prefs.edit().clear().commit()
        val ledger=SignLedger(prefs);val coordinator=SignSubmission(ledger)
        var sends=0;var reply="二维码已过期";var lost=false
        val api=object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.QR,"扫码")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit)=throw AssertionError()
            override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
                beforeCommit();sends++;if(lost)throw IOException("synthetic loss");return SignProtocol.outcome(reply)
            }
            override fun cancel(){}
        }
        fun submit(owner:String)=coordinator.submit(owner,item,api,SignInputs(qr=qr,expectedMode=SignMode.QR)){}
        assertTrue(submit("one").retryAllowed);assertFalse(ledger.attempted("one",item))
        reply="success";assertTrue(submit("one").successful);assertTrue(ledger.attempted("one",item))
        assertEquals(SignResultCategory.ALREADY_ATTEMPTED,submit("one").category);assertEquals(2,sends)
        lost=true;assertEquals(SignResultCategory.UNKNOWN,submit("two").category);assertTrue(ledger.attempted("two",item))
        assertEquals(SignResultCategory.ALREADY_ATTEMPTED,submit("two").category);assertEquals(3,sends)
        assertFalse(SignProtocol.outcome("<html>二维码已过期</html>").retryAllowed)
        prefs.edit().clear().commit()
    }
    @Test fun materialValuesAreRedactedEvenIfAccidentallyIncludedInDiagnosticText() {
        val safe=DiagnosticLog.sanitize("signCode=258 enc=FACDD34DBC291EE176 gesture=14789 latitude=1.3521 longitude=103.8198")
        for(secret in listOf("258","FACDD34DBC291EE176","14789","1.3521","103.8198"))assertFalse(safe.contains(secret))
    }
    @Test fun wechatIdentityAndSignedNotificationValuesAreNeverExportedInLogs() {
        val safe=DiagnosticLog.sanitize("openid=private-open-value puid=private-user-value unionid=private-union-value wxsn=private-signature-value _wtime=private-time-value")
        for(secret in listOf("private-open-value","private-user-value","private-union-value","private-signature-value","private-time-value"))assertFalse(safe.contains(secret))
        assertTrue(safe.contains("wxsn=[redacted]"))
    }
    @Test fun combinedRequirementsExplainAllBlockersInsteadOfOnlyTheFirstOne() {
        val root=JSONObject(detail(verification=true));root.getJSONObject("data").put("openCheckFaceFlag",1).put("openCheckWeChatFlag",1).put("openPreventCheatFlag",1).put("ifrefreshewm",1)
        val plan=SignProtocol.detail(root.toString(),item,allowQr=true)
        assertEquals(SignMode.OFFICIAL,plan.mode)
        for(label in listOf("验证码","人脸核验","微信身份校验","防作弊校验"))assertTrue(plan.message.contains(label))
        assertTrue(plan.requirements.lines.any{it.contains("二维码自动更新：已开启")})
        assertFalse(plan.requirements.lines.any{it.contains("5 秒")})
    }
    @Test fun responseDiagnosticsKeepSchemaAndErrorCodeButNeverGenericAuthorizationCode() {
        DiagnosticLog.init(RuntimeEnvironment.getApplication() as BellApp);DiagnosticLog.clear()
        SignProtocol.traceResponse("{\"result\":0,\"code\":\"581246\",\"errorCode\":90002,\"message\":\"private response\"}","check_code")
        val log=DiagnosticLog.snapshot()
        assertTrue(log.contains("errorCode=90002"));assertTrue(log.contains("result=0"))
        assertFalse(log.contains("581246"));assertFalse(log.contains("private response"))
    }
}
