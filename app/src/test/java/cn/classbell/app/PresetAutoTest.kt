package cn.classbell.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class PresetAutoTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val sign=SignActivity("301","合成拍照签到",course,otherId=0)
    private val owner=accountKey("preset-fixture")
    private val calls=AtomicInteger()
    private var uploaded:File?=null
    @Before fun setup(){app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,owner,listOf(course))}
    @After fun cleanup(){app.savedSignPhotos.clear(owner);app.savedSignPhotos.clear("other");app.prefs.edit().clear().commit()}
    private fun save(identity:String=owner,value:String="selected-image") {
        val file=File.createTempFile("fixture-",".jpg",app.cacheDir).apply{writeText(value)}
        try{app.savedSignPhotos.replace(identity,file)}finally{file.delete()}
    }
    private fun enable(){app.prefs.edit().putBoolean("autoPhoto.$owner",true).commit()}
    private fun actions(mode:SignMode=SignMode.PHOTO,plan:()->Unit={},submit:(File?)->Unit={}):SigningActions=SigningActions(app){guard->object:SignSource{
        override fun activities(course:Course)=listOf(sign)
        override fun plan(activity:SignActivity):SignPlan{guard();plan();return SignPlan(mode,"合成要求")}
        override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome{
            guard();beforeCommit();guard();uploaded=photo;calls.incrementAndGet();submit(photo);return SignOutcome(true,"签到成功")
        }
        override fun cancel(){}
    }}
    @Test fun photoOptInIsIndependentAndUploadsOnlyAnImmutablePrivateCopyOnce() {
        save();enable()
        val actions=actions(submit={assertEquals("selected-image",it!!.readText());assertNotEquals(app.savedSignPhotos.file(owner),it)})
        actions.autoSubmit(sign){true};actions.autoSubmit(sign){true}
        assertEquals(1,calls.get());assertFalse(uploaded!!.exists());assertTrue(app.savedSignPhotos.file(owner)!!.exists())
        assertFalse(app.prefs.getBoolean("autoNormal.$owner",false))
    }
    @Test fun noPhotoNoOptInAndWrongKindDoNotUpload() {
        actions().autoSubmit(sign){true};assertEquals(0,calls.get())
        enable();actions().autoSubmit(sign.copy(id="302")){true};assertEquals(0,calls.get())
        save();actions(SignMode.NORMAL).autoSubmit(sign.copy(id="303")){true}
        actions(SignMode.OFFICIAL).autoSubmit(sign.copy(id="304")){true}
        actions().autoSubmit(sign.copy(id="305",kind=ReminderKind.CLASSROOM)){true}
        assertEquals(0,calls.get())
    }
    @Test fun clearingDisablesPhotoAutomationAndPreservesASeparateAccount() {
        save();save("other","another-image");enable()
        app.savedSignPhotos.clear(owner)
        assertNull(app.savedSignPhotos.file(owner));assertFalse(app.prefs.getBoolean("autoPhoto.$owner",true))
        assertEquals("another-image",app.savedSignPhotos.file("other")!!.readText())
    }
    @Test fun replacementCannotMutateAnAlreadyLeasedImage() {
        save();val old=app.savedSignPhotos.version(owner);val lease=app.savedSignPhotos.lease(owner)!!
        try{save(value="replacement");assertEquals("selected-image",lease.readText());assertNotEquals(old,app.savedSignPhotos.version(owner))}
        finally{lease.delete()}
        assertEquals("replacement",SavedSignPhotos(app,app.prefs).file(owner)!!.readText())
    }
    @Test fun stopAccountChangeAndClearDuringPreflightBlockCommit() {
        save();enable();var running=true
        actions(plan={running=false}).autoSubmit(sign){running}
        actions(plan={app.savedSignPhotos.clear(owner)}).autoSubmit(sign.copy(id="302")){true}
        save();enable()
        actions(plan={app.prefs.edit().putString("identity","another-account").commit()}).autoSubmit(sign.copy(id="303")){true}
        assertEquals(0,calls.get())
    }
    @Test fun lostReplyNeverRepeatsUploadAndDeletesTheLeasedCopy() {
        save();enable();val actions=actions(submit={throw java.io.IOException("synthetic lost reply")})
        actions.autoSubmit(sign){true};actions.autoSubmit(sign){true}
        assertEquals(1,calls.get());assertFalse(uploaded!!.exists())
        assertTrue(actions.ledger.description(owner).contains("不自动重试"))
    }
    @Test fun changedTypeCannotBorrowThePhotoOptInForNormalSubmission() {
        save();enable();var checks=0
        val actions=SigningActions(app){guard->object:SignSource {
            override fun activities(course:Course)=listOf(sign)
            override fun plan(activity:SignActivity):SignPlan {guard();return SignPlan(if(checks++==0)SignMode.PHOTO else SignMode.NORMAL,"已变更")}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {beforeCommit();calls.incrementAndGet();return SignOutcome(true,"签到成功")}
            override fun cancel(){}
        }}
        actions.autoSubmit(sign){true}
        assertEquals(0,calls.get());assertFalse(actions.ledger.attempted(owner,sign))
        assertTrue(actions.ledger.description(owner).contains("要求已变化"))
    }

}
