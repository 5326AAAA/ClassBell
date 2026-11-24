package cn.classbell.app

import android.content.Intent
import android.os.SystemClock
import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic protocol fixtures, not a claim of latency or acceptance at the live service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class QrFastSubmissionTest {
    private val item=SignActivity("301","五秒动态码用例",Course("101","201","合成课程"),otherId=2)
    private val raw="https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=FACDD34DBC291EE176"
    private fun qr()=checkNotNull(SignQr.parse(raw))
    private fun ledger(name:String):SignLedger {
        val preferences=RuntimeEnvironment.getApplication().getSharedPreferences("fast-qr-$name",0)
        preferences.edit().clear().commit()
        return SignLedger(preferences)
    }
    private fun detail(dynamic:Boolean=true)=JSONObject().put("result",1).put("data",JSONObject()
        .put("id",301).put("courseId",101).put("clazzid",201).put("activeType",2).put("status",1)
        .put("otherId",2).put("ifphoto",0).put("configJson","{}")
        .put("ifNeedVCode",0).put("showVCode",0).put("ifopenAddress",0)
        .put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0).put("openPreventCheatFlag",0)
        .put("openSignOutFlag",0).put("ifrefreshewm",if(dynamic)1 else 0).put("ewmRefreshTime",5)).toString()

    private fun fixture(request:Request,uid:String,dynamic:Boolean=true):String=when(request.url.encodedPath) {
        "/v2/apis/active/student/activelist"->"""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":2,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo"->detail(dynamic)
        "/apis/login/userLogin4Uname.do"->JSONObject().put("msg",JSONObject().put("uid",uid).put("name","合成学生").put("fid","101")).toString()
        "/newsign/preSign"->"<html>准备页面</html>"
        "/pptSign/stuSignajax"->"success"
        else->throw AssertionError("Unexpected protocol fixture path ${request.url.encodedPath}")
    }
    private fun api(uid:String="901",check:()->Unit={},exchange:(Request)->String)=SigningApi(
        listOf(Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build()),
        check,allowQr=true,exchange=exchange)

    @Test fun dynamicPreparationLeavesOnlyCurrentRcodePreparationAndFinalSubmissionAfterCapture() {
        val requests=mutableListOf<Request>()
        val api=api {request->requests+=request;fixture(request,"901")}
        try {
            val prepared=api.prepareQr(item)
            assertEquals(5,prepared.plan.qrRefresh.seconds)
            assertEquals(true,prepared.plan.qrRefresh.enabled)
            assertTrue(requests.any{it.url.encodedPath.endsWith("userLogin4Uname.do")})
            assertTrue(requests.any{it.url.encodedPath.endsWith("preSign")})
            assertFalse(requests.any{it.url.encodedPath.endsWith("stuSignajax")})
            requests.clear()
            val material=qr()
            val start=System.nanoTime()
            val result=SignSubmission(ledger("prepared")).submit("owner",item,api,
                SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared)){}
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
            assertTrue(result.successful)
            assertTrue("Local prepared flow took ${elapsed}ms",elapsed<5_000)
            assertEquals(listOf("/newsign/preSign","/pptSign/stuSignajax"),requests.map{it.url.encodedPath})
            val rcode="SIGNIN:aid=301&source=15&Code=123456&enc=${material.enc}"
            val preSign=requests.first()
            assertEquals(rcode,preSign.url.queryParameter("rcode"))
            assertFalse("rcode must be query-encoded exactly once",preSign.url.encodedQuery.orEmpty().contains("%253A",ignoreCase=true))
            assertNull(preSign.url.queryParameter("signCode"))
            val final=requests.last()
            assertEquals("/pptSign/stuSignajax",final.url.encodedPath)
            assertEquals("901",final.url.queryParameter("uid"))
            assertEquals("301",final.url.queryParameter("activeId"))
            assertEquals(material.enc,final.url.queryParameter("enc"))
            assertNull(final.url.queryParameter("signCode"))
            assertNotNull(final.tag(QrDispatchTrace::class.java))
            assertEquals(material.capturedAt,final.tag(QrDispatchTrace::class.java)!!.capturedAt)
        } finally {api.cancel()}
    }

    @Test fun staticQrNeedsOnlyTheFinalRequestAfterPreparation() {
        val requests=mutableListOf<Request>()
        val api=api{request->requests+=request;fixture(request,"901",dynamic=false)}
        try {
            val prepared=api.prepareQr(item)
            assertEquals(false,prepared.plan.qrRefresh.enabled)
            requests.clear()
            val material=qr()
            assertTrue(SignSubmission(ledger("static")).submit("owner",item,api,
                SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared)){}.successful)
            assertEquals(listOf("/pptSign/stuSignajax"),requests.map{it.url.encodedPath})
            assertNull(requests.single().url.queryParameter("rcode"))
        } finally {api.cancel()}
    }

    @Test fun dynamicRcodePreparationCannotSpendTheDeadlineThenSendAnExpiredFinalRequest() {
        val afterCapture=AtomicBoolean(false)
        val finalSends=AtomicInteger()
        val api=api{request->
            if(afterCapture.get() && request.url.encodedPath=="/newsign/preSign")
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(4_501))
            if(request.url.encodedPath.endsWith("stuSignajax"))finalSends.incrementAndGet()
            fixture(request,"901")
        }
        val ledger=ledger("dynamic-budget")
        try {
            val prepared=api.prepareQr(item)
            val material=qr()
            afterCapture.set(true)
            val result=SignSubmission(ledger).submit("owner",item,api,
                SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared)){}
            assertEquals(SignResultCategory.NOT_SUBMITTED,result.category)
            assertEquals(0,finalSends.get())
            assertFalse("Preflight must not reserve the actual submission",ledger.attempted("owner",item))
        } finally {api.cancel()}
    }

    @Test fun scannerRetainsBothUrlCAndSigninCodeAsQrMaterialAndRejectsAmbiguousOrForeignSources() {
        assertEquals("123456",qr().code)
        val legacy=SignQr.parse("SIGNIN:aid=301&source=15&Code=654321&enc=FACDD34DBC291EE176")!!
        assertEquals("654321",legacy.code)
        assertEquals("301",legacy.activityId)
        assertNull(SignQr.parse(raw+"&Code=654321"))
        assertNull(SignQr.parse(raw+"&source=999"))
        val safe=DiagnosticLog.sanitize("rcode=SIGNIN:aid=301&source=15&Code=654321&enc=FACDD34DBC291EE176 Code=654321 c=123456")
        assertFalse(safe.contains("FACDD34DBC291EE176"))
        assertFalse(safe.contains("654321"))
        assertFalse(safe.contains("123456"))
        assertEquals("SignQr(redacted)",legacy.toString())
    }

    @Test fun rejectedQrWaitsForNewMaterialAndOnlyReturnsTheNewCapture() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        app.prefs.edit().clear().commit()
        app.courseMemory.activate(app.prefs,accountKey("fresh-qr-scanner"),listOf(item.course))
        val rejected=qr()
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId",item.id)
            .putExtra(QrScanActivity.REJECTED_ENC,rejected.enc)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup()
        val activity=controller.get()
        try {
            activity.accept(raw)
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).resultIntent)
            assertNull(shadowOf(activity).nextStartedActivity)
            val fresh=raw.replace("FACDD34DBC291EE176","ABCDEF0123456789")
            activity.accept(fresh)
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
            val result=shadowOf(activity).resultIntent
            assertEquals(fresh,result.getStringExtra(QrScanActivity.MATERIAL))
            assertTrue(result.hasExtra(QrScanActivity.CAPTURED_AT))
        } finally {controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }

    @Test fun preparationCannotBeTransferredToAnotherAccountOrActivity() {
        val sends=AtomicInteger()
        fun source(uid:String)=api(uid){request->
            if(request.url.encodedPath.endsWith("stuSignajax"))sends.incrementAndGet()
            fixture(request,uid)
        }
        val first=source("901");val second=source("902")
        try {
            val prepared=first.prepareQr(item)
            assertThrows(ApiProblem::class.java){prepared.submit(second,item,qr(),null){}}
            assertThrows(ApiProblem::class.java){prepared.submit(first,item.copy(id="302"),qr(),null){}}
            assertEquals(0,sends.get())
        } finally {first.cancel();second.cancel()}
    }

    @Test fun captureTimestampSurvivesResultDeliveryAndLateMaterialIsNotSentOrReserved() {
        val sends=AtomicInteger()
        val api=api{request->
            if(request.url.encodedPath.endsWith("stuSignajax"))sends.incrementAndGet()
            fixture(request,"901")
        }
        val ledger=ledger("deadline")
        try {
            val prepared=api.prepareQr(item)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
            val capturedAt=SystemClock.elapsedRealtime()-4_501
            val material=checkNotNull(SignQr.fromResult(Intent().putExtra(QrScanActivity.MATERIAL,raw)
                .putExtra(QrScanActivity.CAPTURED_AT,capturedAt)))
            assertEquals(capturedAt,material.capturedAt)
            val result=SignSubmission(ledger).submit("owner",item,api,
                SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared)){}
            assertEquals(SignResultCategory.NOT_SUBMITTED,result.category)
            assertEquals(0,sends.get())
            assertFalse(ledger.attempted("owner",item))
            val future=qr().apply{this.capturedAt=SystemClock.elapsedRealtime()+1}
            assertThrows(ApiProblem::class.java){prepared.submit(api,item,future,null){}}
        } finally {api.cancel()}
    }

    @Test fun cancellationAfterPreparationNeverBorrowsOrSendsAnOldAccountSession() {
        val active=AtomicBoolean(true);val sends=AtomicInteger()
        val api=api(check={if(!active.get())throw java.util.concurrent.CancellationException()}){request->
            if(request.url.encodedPath.endsWith("stuSignajax"))sends.incrementAndGet()
            fixture(request,"901")
        }
        try {
            val prepared=api.prepareQr(item)
            active.set(false)
            assertThrows(java.util.concurrent.CancellationException::class.java){prepared.submit(api,item,qr(),null){}}
            assertEquals(0,sends.get())
        } finally {api.cancel()}
    }

    @Test fun expiredTextAndJsonWithInstructionsPermitANewScanButHtmlAndUnknownRepliesDoNot() {
        listOf("二维码已失效，请重新扫描", "二维码已过期，请重新扫码", "二维码无效，请刷新后重试",
            """{"status":false,"mes":"二维码已失效，请重新扫描"}""",
            """{"result":0,"msg":"二维码已过期，请重新扫码"}""").forEach{body->
            val outcome=SignProtocol.outcome(body)
            assertEquals(body,SignResultCategory.FAILED,outcome.category)
            assertTrue(body,outcome.retryAllowed)
        }
        listOf("<html>二维码已过期</html>","unrecognized reply").forEach{body->
            assertFalse(SignProtocol.outcome(body).retryAllowed)
            assertFalse(SignProtocol.outcome(body).successful)
        }
    }

    @Test fun unfinishedRequestCannotBeUnlockedButCompletedUnknownCanRecoverFromMatchedNotSignedResult() {
        val ledger=ledger("recovery")
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val loseReply=AtomicBoolean(true)
        val sends=AtomicInteger()
        val source=object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.QR,"扫码")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit)=throw AssertionError("Wrong route")
            override fun submitQr(activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
                beforeCommit();sends.incrementAndGet();entered.countDown()
                if(loseReply.get()){check(finish.await(3,TimeUnit.SECONDS));throw IOException("Synthetic lost reply")}
                return SignOutcome(true,"已确认")
            }
            override fun cancel(){}
        }
        val coordinator=SignSubmission(ledger)
        val executor=Executors.newSingleThreadExecutor()
        val notSigned=SignOutcome(false,"合成未签到结果",SignResultCategory.NOT_SUBMITTED)
        try {
            val first=executor.submit<SignOutcome>{coordinator.submit("owner",item,source,SignInputs(qr=qr(),expectedMode=SignMode.QR)){}}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertFalse(ledger.recoverUnsubmittedQr("owner",item,notSigned))
            assertEquals(SignResultCategory.ALREADY_ATTEMPTED,coordinator.submit("owner",item,source,SignInputs(qr=qr())){}.category)
            finish.countDown()
            assertEquals(SignResultCategory.UNKNOWN,first.get(3,TimeUnit.SECONDS).category)
            assertTrue(ledger.attempted("owner",item))
            assertFalse(ledger.recoverUnsubmittedQr("owner",item,SignOutcome(false,"查询失败",SignResultCategory.UNKNOWN)))
            assertTrue(ledger.recoverUnsubmittedQr("owner",item,notSigned))
            assertFalse(ledger.attempted("owner",item))
            loseReply.set(false)
            assertTrue(coordinator.submit("owner",item,source,SignInputs(qr=qr())){}.successful)
            assertFalse(ledger.recoverUnsubmittedQr("owner",item,notSigned))
            assertTrue(ledger.attempted("owner",item))
            assertEquals(2,sends.get())
        } finally {finish.countDown();executor.shutdownNow()}
    }

    @Test fun refreshSecondsAreReadExplicitlyAndUnknownOrConflictingValuesAreNotInvented() {
        val five=QrRefreshInfo.read(JSONObject("""{"ifrefreshewm":1,"ewmRefreshTime":5}"""))
        assertEquals(5,five.seconds);assertEquals(true,five.enabled)
        assertTrue(five.description().contains("5 秒"))
        assertNull(QrRefreshInfo.read(JSONObject("""{"ifrefreshewm":1,"timeLong":5}""")).seconds)
        assertNull(QrRefreshInfo.read(JSONObject("""{"ewmRefreshTime":5,"ewmrefreshtime":10}""")).seconds)
        assertNull(QrRefreshInfo.read(JSONObject("""{"ewmRefreshTime":0}""")).seconds)
        assertNull(QrRefreshInfo.read(JSONObject("""{"ifrefreshewm":1,"ifRefreshEwm":0}""")).enabled)
    }

    @Test fun notSignedRecoveryIsLimitedToQrAndNeverReleasesAnUnrelatedOrNonQrReservation() {
        val ledger=ledger("recovery-scope")
        val notSigned=SignOutcome(false,"未签到",SignResultCategory.NOT_SUBMITTED)
        val normal=item.copy(otherId=0)
        ledger.reserve("owner",normal)
        assertFalse(ledger.recoverUnsubmittedQr("owner",normal,notSigned))
        assertTrue(ledger.attempted("owner",normal))
        val different=item.copy(id="302")
        assertTrue(ledger.recoverUnsubmittedQr("other",different,notSigned))
        assertTrue(ledger.attempted("owner",normal))
    }

    @Test fun tenPreparedAccountRequestsOverlapWithinFiveSecondsAndKeepTheirOwnIdentityAndResult() {
        val count=10
        val allSending=CountDownLatch(count)
        val dispatched=ConcurrentLinkedQueue<Pair<String,Long>>()
        val sources=(1..count).map {index->
            val uid=(900+index).toString()
            api(uid){request->
                if(request.url.encodedPath.endsWith("stuSignajax")) {
                    assertEquals(uid,request.url.queryParameter("uid"))
                    assertEquals("301",request.url.queryParameter("activeId"))
                    dispatched+=uid to System.nanoTime()
                    allSending.countDown()
                    assertTrue("Prepared account requests were serialized",allSending.await(3,TimeUnit.SECONDS))
                    if(index==count)"二维码已过期，请重新扫码" else "success"
                } else fixture(request,uid)
            }
        }
        val ledger=ledger("ten-accounts")
        val executor=Executors.newFixedThreadPool(count)
        try {
            val prepared=sources.map{it.prepareQr(item)}
            val material=qr()
            val start=System.nanoTime()
            val results=sources.mapIndexed{index,source->executor.submit<SignOutcome>{
                SignSubmission(ledger).submit("account-$index",item,source,
                    SignInputs(qr=material,expectedMode=SignMode.QR,preparedQr=prepared[index])){}
            }}.map{it.get(5,TimeUnit.SECONDS)}
            assertEquals(count,dispatched.size)
            assertEquals(count,dispatched.map{it.first}.toSet().size)
            assertTrue("Local dispatch exceeded 5s",dispatched.all{TimeUnit.NANOSECONDS.toMillis(it.second-start)<5_000})
            assertEquals(count-1,results.count{it.successful})
            assertTrue(results.last().retryAllowed)
            assertFalse(ledger.attempted("account-9",item))
            (0 until count-1).forEach{assertTrue(ledger.attempted("account-$it",item))}
        } finally {allSending.countDown();executor.shutdownNow();sources.forEach{it.cancel()}}
    }
}
