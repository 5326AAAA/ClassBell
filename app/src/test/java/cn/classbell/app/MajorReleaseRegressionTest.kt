package cn.classbell.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class MajorReleaseRegressionTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","交互式软件系统设计")
    private val sign=SignActivity("301","合成签到",course,otherId=0)
    @Before fun reset(){app.prefs.edit().clear().commit()}
    private fun task(courseName:String="交互式软件系...")="""<li data="https://mobilelearn.chaoxing.com/widget/renwu/proxyRenwuAttendUrl?activeId=901&amp;DB_STRATEGY=PRIMARY_KEY&amp;STRATEGY_PARA=activeId&amp;returnType=1&amp;ut=s"><div role="option"><p>实验1</p><span class="status">未完成</span><span>《$courseName》</span></div></li>"""
    @Test fun activityHomeworkDoesNotNeedFakeCourseOrWorkIdsAndSurvivesCacheRoundTrip() {
        val snapshot=HomeworkParser.parse(task(),listOf(course),1000)
        val item=snapshot.items.single()
        assertEquals(0,snapshot.skippedRows);assertTrue(item.activityTask)
        assertEquals(course.name,item.courseName);assertEquals(course.key,item.courseKey)
        assertEquals("901",item.id);assertEquals(snapshot,HomeworkSnapshot.fromJson(snapshot.json()))
        assertEquals(item.key,HomeworkParser.parse(task(),emptyList(),1000).items.single().key)
        assertFalse(HomeworkParser.activityTaskUrl(item.url.replace("mobilelearn.chaoxing.com","example.invalid")))
    }
    @Test fun manualProgressIsAccountScopedSurvivesRefreshAndCanFollowServerAgain() {
        val item=HomeworkParser.parse(task(),listOf(course),1000).items.single()
        val progress=HomeworkProgress(app.prefs)
        progress.set("account-a",item,HomeworkMark.COMPLETE)
        assertEquals(HomeworkState.SUBMITTED,HomeworkProgress.state(item,2000,progress.marks("account-a")))
        assertEquals(HomeworkState.PENDING,HomeworkProgress.state(item,2000,progress.marks("account-b")))
        val refreshed=item.copy(sourceStatus="已完成",fetchedAt=3000)
        progress.set("account-a",refreshed,HomeworkMark.INCOMPLETE)
        assertEquals(HomeworkState.PENDING,HomeworkProgress.state(refreshed,4000,HomeworkProgress(app.prefs).marks("account-a")))
        progress.set("account-a",refreshed,null)
        assertEquals(HomeworkState.SUBMITTED,HomeworkProgress.state(refreshed,4000,progress.marks("account-a")))
    }
    @Test fun aliasesAndBooleanFlagsAreAcceptedButExplicitForeignClassIsNeverOverridden() {
        val data=JSONObject().put("activeId","301").put("clazzId","201").put("type",2).put("otherid",0).put("ifPhoto",true).put("status",1)
        listOf("ifNeedVCode","showVCode","ifopenAddress","openCheckFaceFlag","openCheckWeChatFlag","openPreventCheatFlag","openSignOutFlag").forEach{data.put(it,false)}
        fun body()=JSONObject().put("result",true).put("data",data).toString()
        var corroborations=0
        assertEquals(SignMode.PHOTO,SignProtocol.detail(body(),sign){corroborations++;true}.mode)
        assertEquals(1,corroborations)
        data.put("clazzId","999")
        assertEquals("CLASS_ID_MISMATCH",SignProtocol.detail(body(),sign){fail("Must not replace explicit foreign class");true}.issueCode)
    }
    @Test fun preparationFailureCanBeRetriedButLostFinalReplyCannotAndAnotherAccountRemainsIndependent() {
        val ledger=SignLedger(app.prefs);val submission=SignSubmission(ledger)
        var failPreparation=true;var sent=0
        val source=object:SignSource {
            override fun activities(course:Course)=listOf(sign)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.NORMAL,"普通")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                if(failPreparation)throw IOException("synthetic preparation failure")
                beforeCommit();sent++;throw IOException("synthetic reply loss")
            }
            override fun cancel(){}
        }
        assertEquals(SignResultCategory.NOT_SUBMITTED,submission.submit("a",sign,source,SignInputs()){}.category)
        assertFalse(ledger.attempted("a",sign))
        failPreparation=false
        assertEquals(SignResultCategory.UNKNOWN,submission.submit("a",sign,source,SignInputs()){}.category)
        assertEquals(SignResultCategory.ALREADY_ATTEMPTED,submission.submit("a",sign,source,SignInputs()){}.category)
        assertEquals(1,sent)
        assertEquals(SignResultCategory.UNKNOWN,submission.submit("b",sign,source,SignInputs()){}.category)
        assertEquals(2,sent)
    }
    @Test fun automaticPreflightCooldownDoesNotConsumeManualSubmissionReservation() {
        val ledger=SignLedger(app.prefs)
        assertTrue(ledger.allowAutomaticCheck("a",sign));assertFalse(ledger.allowAutomaticCheck("a",sign))
        assertFalse(ledger.attempted("a",sign));assertTrue(ledger.reserve("a",sign))
        assertFalse(ledger.allowAutomaticCheck("a",sign))
    }
}
