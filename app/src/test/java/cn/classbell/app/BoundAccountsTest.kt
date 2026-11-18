package cn.classbell.app

import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class BoundAccountsTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val activity=SignActivity("301","合成签到",course,otherId=0)
    private var saved:String?=null
    private val store=BoundAccounts({saved},{saved=it})
    private fun cookies(uid:String)=listOf(Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build())
    @Test fun bindingCopiesIndependentSessionsReplacesDuplicatesAndUnbindingInvalidatesSnapshots() {
        val first=store.bind("账号一",cookies("901"),listOf(course));val second=store.bind("账号二",cookies("902"),listOf(course))
        assertEquals(listOf("901","902"),store.list().map{it.cookies.single().value})
        assertEquals(listOf("账号一","账号二"),BoundAccounts({saved},{saved=it}).list().map{it.label})
        val updated=store.bind("账号一已更新",cookies("901"),listOf(course))
        assertEquals(2,store.list().size);assertFalse(store.current(first));assertTrue(store.current(updated))
        store.unbind(second.id);assertFalse(store.current(second));assertEquals(1,store.list().size)
        assertFalse(saved!!.contains("password"))
    }
    @Test fun maximumAccountsAndMissingIdentityCannotOverwriteTheExistingList() {
        for(id in 901 until 901+BoundAccounts.MAX_ACCOUNTS)store.bind("合成账号",cookies(id.toString()),listOf(course))
        assertThrows(ApiProblem::class.java){store.bind("超出上限",cookies("999"),listOf(course))}
        assertThrows(LoginRequired::class.java){store.bind("无身份",emptyList(),listOf(course))}
        assertEquals(BoundAccounts.MAX_ACCOUNTS,store.list().size)
    }
    @Test fun sourceUsesChosenCookiesAndUnbindingBetweenPlanAndCommitBlocksSubmission() {
        app.prefs.edit().clear().commit()
        val selected=store.bind("所选账号",cookies("901"),listOf(course));var submits=0
        val signing=BoundSigning(store,SignLedger(app.prefs)){snapshot,guard->
            assertEquals("901",snapshot.single().value)
            object:SignSource {
                override fun activities(course:Course)=listOf(activity)
                override fun plan(activity:SignActivity)=SignPlan(SignMode.NORMAL,"普通签到")
                override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome{
                    store.unbind(selected.id);guard();beforeCommit();submits++;return SignOutcome(true,"签到成功")
                }
                override fun cancel(){}
            }
        }
        val source=signing.source(selected)
        val result=signing.submit(selected,activity,"",source){}
        assertFalse(result.successful);assertEquals(0,submits)
    }
    @Test fun unknownOutcomeCannotBeRepeatedAndForeignCourseIsRejected() {
        app.prefs.edit().clear().commit()
        val selected=store.bind("合成账号",cookies("901"),listOf(course));var submits=0
        val signing=BoundSigning(store,SignLedger(app.prefs)){_,guard->object:SignSource{
            override fun activities(course:Course)=listOf(activity)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.NORMAL,"普通签到")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome{guard();beforeCommit();submits++;throw java.io.IOException()}
            override fun cancel(){}
        }}
        val api=signing.source(selected)
        assertFalse(signing.submit(selected,activity,"",api){}.successful)
        assertFalse(signing.submit(selected,activity,"",api){}.successful);assertEquals(1,submits)
        assertThrows(ApiProblem::class.java){signing.submit(selected,activity.copy(course=Course("9","8","其他课程")),"",api){}}
    }
    @Test fun damagedStorageIsNeverOverwrittenByBindingOrUnbinding() {
        saved="[{broken]";val original=saved
        assertTrue(store.list().isEmpty())
        assertThrows(ApiProblem::class.java){store.bind("新账号",cookies("901"),listOf(course))}
        assertEquals(original,saved)
        assertThrows(ApiProblem::class.java){store.unbind("any")}
        assertEquals(original,saved)
    }
}
