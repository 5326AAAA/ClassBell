package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SigningPanelTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val controller=Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity:Activity
    private lateinit var panel:SigningPanel
    private val course=Course("101","201","合成课程")
    private val target=SignActivity("301","合成拍照签到",course)
    private var historyFixture=listOf(CourseActivity(target,2,2,"已结束"),CourseActivity(target.copy(id="302"),2,1,"进行中"))
    private val submitted=AtomicInteger()
    private val queried=AtomicInteger()
    private var submissionOutcome=SignOutcome(true,"签到成功")
    private var queryOutcome=SignOutcome(true,"学习通已确认当前账号签到成功。")
    private var submittedFile:File?=null
    private var identity=""
    private var pickedIntent:Intent?=null
    private var pickedCode=-1
    private var photoController:ActivityController<SignActionActivity>?=null
    private val photoActivity get()=checkNotNull(photoController).get()
    @Before fun setup() {
        app.prefs.edit().clear().commit()
        app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()
        identity=accountKey("photo-panel")
        app.courseMemory.activate(app.prefs,identity,listOf(course))
        activity=controller.setup().get()
        panel=SigningPanel(activity,app,{},::source)
        activity.setContentView(panel)
    }
    private fun source(guard:()->Unit):SignSource=object:SignSource {
            override fun activities(course:Course):List<SignActivity> {guard();return listOf(target)}
            override fun activityFeed(course:Course):List<CourseActivity>{
                guard()
                return historyFixture
            }
            override fun overview(activity:SignActivity):SignOverview {
                guard()
                return SignOverview("合成拍照签到","拍照签到",2,1735693200000L,null,1735695000000L,15)
            }
            override fun plan(activity:SignActivity):SignPlan {guard();return SignPlan(SignMode.PHOTO,"拍照")}
            override fun attendance(activity:SignActivity):SignOutcome {guard();queried.incrementAndGet();return queryOutcome}
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit();guard();assertTrue(photo!!.isFile);submittedFile=photo;submitted.incrementAndGet()
                return submissionOutcome
            }
            override fun cancel() {}
    }
    @After fun cleanup() {photoController?.pause()?.stop()?.destroy();panel.close();app.savedSignPhotos.clear(identity);controller.pause().stop().destroy();app.prefs.edit().clear().commit();app.getSharedPreferences("course_memory",Context.MODE_PRIVATE).edit().clear().commit()}
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(text:String)=views(panel).filterIsInstance<Button>().single{it.text.toString()==text || it.contentDescription==text}
    private fun text()=views(panel).filterIsInstance<TextView>().joinToString("\n"){it.text}
    private fun photoViews()=views(photoActivity.window.decorView)
    private fun photoButton(text:String)=photoViews().filterIsInstance<Button>().single{it.text==text}
    private fun photoResult(uri:Uri?) {
        SignActionActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply{isAccessible=true}.invoke(photoActivity,SignActionActivity.PHOTO,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(uri))
    }
    private fun await(condition:()->Boolean) {
        val end=System.nanoTime()+5_000_000_000
        while(System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)}
        val visibleText=photoController?.let { photoViews().filterIsInstance<TextView>().joinToString(" / "){it.text} } ?: text()
        fail("Timed out waiting for controlled panel action. UI: $visibleText")
    }
    private fun pick() {
        button("更新进行中的签到").performClick()
        await {text().contains("显示 1 项")}
        button("查看要求并处理签到").performClick()
        val page=checkNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(SignActionActivity::class.java.name,page.component?.className)
        assertTrue(page.getBooleanExtra("monitoredPhoto",false))
        photoController=Robolectric.buildActivity(SignActionActivity::class.java,page).apply {
            get().sourceFactory=::source
            setup()
        }
        await {photoViews().filterIsInstance<Button>().any{it.text=="从相册选择本次照片"}}
        photoButton("从相册选择本次照片").performClick()
        await {
            val request=shadowOf(photoActivity).nextStartedActivityForResult
            if(request!=null) {pickedIntent=request.intent;pickedCode=request.requestCode;true} else false
        }
        // Robolectric maintains separate queues for ordinary and for-result launches.
        assertEquals(pickedIntent?.action,shadowOf(photoActivity).nextStartedActivity?.action)
    }
    @Test fun sectionsExposeOneTaskAtATimeAndRememberWhereTheUserWas(){
        assertTrue(button("进行中").isSelected)
        fun visible(view:View):Boolean=generateSequence(view){it.parent as? View}.all{it.visibility==View.VISIBLE}
        assertTrue(visible(button("更新进行中的签到")))
        assertFalse(visible(button("预选 / 更换相册照片")))
        button("设置").performClick();assertTrue(button("设置").isSelected)
        assertTrue(visible(button("预选 / 更换相册照片")));assertFalse(visible(button("更新进行中的签到")))
        assertTrue(text().contains("位置签到 · 不可用"))
        assertTrue(views(panel).filterIsInstance<Button>().none{it.text=="预选 / 更换位置"})
        assertEquals("photo",app.prefs.getString("ui.signingSection",""))
        val reopened=SigningPanel(activity,app,{})
        try{assertTrue(views(reopened).filterIsInstance<Button>().single{it.text=="设置"}.isSelected)}finally{reopened.close()}
        assertEquals(0,submitted.get())
    }
    @Test fun stoppedActivityMovesToHistorySurvivesReopeningAndCanBeRestored() {
        val ended=target.copy(id="303",title="真正已结束的签到")
        historyFixture=listOf(CourseActivity(target,2,1,"进行中"),CourseActivity(ended,2,2,"已结束"))
        val otherCourse=Course("102","202","另一门课")
        app.signing.ledger.setIgnored(identity,target.copy(course=otherCourse,title="别的课已归档"),true)
        button("更新进行中的签到").performClick()
        await {text().contains("显示 1 项")}
        button("不检测这个签到").performClick()
        assertTrue(app.signing.ledger.ignored(identity,target.key))
        assertFalse(text().contains("查看要求并处理签到"))
        button("已结束").performClick()
        assertFalse(text().contains(target.title))
        assertFalse(text().contains("恢复检测这个签到"))
        val reopened=SigningPanel(activity,app,{},::source)
        try {
            assertFalse(views(reopened).filterIsInstance<TextView>().any{it.text==target.title})
            assertTrue(app.signing.ledger.ignoredActivities(identity).any{it.key==target.key})
        } finally {reopened.close()}
        assertTrue(app.signing.ledger.ignoredActivities("another-account").isEmpty())
        button("选择历史签到课程").performClick()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0)
        await {text().contains("查看签到结果")}
        assertEquals(1,text().lines().count{it==target.title})
        assertTrue(text().indexOf(ended.title)<text().indexOf(target.title))
        assertTrue(text().contains("已停止检测"))
        assertFalse(text().contains("别的课已归档"))
        assertFalse(text().contains("查看签到详情"))
        preview(panel,"已结束-停止检测")
        button("恢复检测这个签到").performClick()
        assertFalse(app.signing.ledger.ignored(identity,target.key))
        assertFalse(text().contains("已停止检测"))
        button("进行中").performClick()
        assertTrue(button("查看要求并处理签到").isShown)
        assertEquals(0,submitted.get())
    }
    @Test fun existingIgnoreKeysGainAnArchiveEntryWhenTheActivityListIsRefreshed() {
        app.prefs.edit().putStringSet("sign.ignored.$identity",setOf(accountKey(target.key))).commit()
        button("更新进行中的签到").performClick()
        await {text().contains("可在「已结束」查看")}
        button("已结束").performClick()
        assertFalse(text().contains("恢复检测这个签到"))
        button("选择历史签到课程").performClick()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0)
        await {text().contains("查看签到结果")}
        assertEquals(listOf(target.key),app.signing.ledger.ignoredActivities(identity).map{it.key})
        assertEquals(1,text().lines().count{it==target.title})
        assertTrue(button("恢复检测这个签到").isShown)
        assertFalse(text().contains("查看要求并处理签到"))
    }
    private fun syntheticPhoto():Uri {
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(ModernUi.soft)
        val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val resolver=photoController?.get()?.contentResolver ?: activity.contentResolver
        return Uri.parse("content://test/selected-image").also{shadowOf(resolver).registerInputStream(it,ByteArrayInputStream(bytes))}
    }
    @Test fun openedHistoryQueriesAttendanceWithoutShowingSubmissionControls() {
        button("已结束").performClick()
        button("选择历史签到课程").performClick()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0)
        await {text().contains("查看签到结果")}
        button("查看签到结果").performClick()
        val intent=checkNotNull(shadowOf(activity).nextStartedActivity)
        assertTrue(intent.getBooleanExtra("historyOnly",false))
        photoController=Robolectric.buildActivity(SignActionActivity::class.java,intent).apply {
            get().sourceFactory=::source
            setup()
        }
        await { photoViews().filterIsInstance<TextView>().any{it.text=="签到结果 · 成功"} }
        assertEquals(1,queried.get())
        assertEquals(0,submitted.get())
        assertFalse(photoViews().filterIsInstance<Button>().any {
            it.text in setOf("重新核对签到要求","从相册选择本次照片","确认并提交签到")
        })
        assertTrue(photoViews().filterIsInstance<TextView>().any{it.text=="15 人"})
        preview(photoActivity.window.decorView,"已结束签到-合成详情")
        assertNull(shadowOf(photoActivity).nextStartedActivity)
        photoButton("分享活动提醒").performClick()
        val chooser=checkNotNull(shadowOf(photoActivity).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        @Suppress("DEPRECATION")
        val share=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND,share.action)
        val sharedText=share.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(sharedText.contains("合成拍照签到"))
        assertEquals(listOf("https://i.chaoxing.com/"),Regex("https?://[^\\s]+").findAll(sharedText).map{it.value}.toList())
    }
    private fun preview(view:View,name:String) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.AT_MOST))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        val bitmap=Bitmap.createBitmap(view.measuredWidth,view.measuredHeight,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);view.draw(canvas)
        val cwd=File(System.getProperty("user.dir"))
        val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun toggleRequiresExplicitOptInEnablesPollingAndIsScopedToAccount() {
        val toggle=views(panel).filterIsInstance<CheckBox>().single{it.text=="普通签到自动提交"}
        assertFalse(toggle.isChecked);toggle.performClick()
        assertTrue(app.prefs.getBoolean("autoNormal.$identity",false));assertTrue(app.prefs.getBoolean("fallbackPolling",false))
        app.prefs.edit().putString("identity",accountKey("other-account")).commit();panel.render()
        assertFalse(toggle.isChecked);assertTrue(app.prefs.getBoolean("autoNormal.$identity",false))
    }
    @Test fun photoButtonOpensSingleImageDocumentPickerWithoutSubmitting() {
        pick()
        val intent=checkNotNull(pickedIntent)
        assertEquals(android.provider.MediaStore.ACTION_PICK_IMAGES,intent.action)
        assertEquals("image/*",intent.type);assertEquals(SignActionActivity.PHOTO,pickedCode)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION !=0)
        photoResult(null);assertEquals(0,submitted.get())
        assertFalse(photoViews().filterIsInstance<Button>().any{it.text=="上传并签到"})
    }
    @Test fun selectionPreviewsAndOnlyExplicitSubmitUsesTheSelectedImage() {
        pick()
        photoResult(syntheticPhoto())
        await {photoViews().any{it.contentDescription=="待提交的签到照片"}}
        preview(photoActivity.window.decorView,"拍照签到-页内照片预览")
        assertEquals(0,submitted.get())
        photoButton("上传并签到").performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(0,submitted.get());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await {photoViews().filterIsInstance<TextView>().any{it.text=="签到成功"}}
        assertEquals(1,submitted.get());assertFalse(submittedFile!!.exists())
        assertFalse(photoViews().filterIsInstance<Button>().any{it.text=="上传并签到" || it.text=="打开学习通签到页面"})
        assertNull(shadowOf(photoActivity).nextStartedActivity)
    }
    @Test fun leavingPhotoPageDeletesPhotoWithoutSubmitting() {
        pick()
        val before=app.cacheDir.listFiles().orEmpty().map{it.name}.toSet()
        photoResult(syntheticPhoto())
        await {photoViews().any{it.contentDescription=="待提交的签到照片"}}
        checkNotNull(photoController).pause().stop().destroy()
        photoController=null
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,submitted.get())
        assertFalse(app.cacheDir.listFiles().orEmpty().any{it.name !in before && it.name.startsWith("sign-photo-")})
    }
    @Test fun confirmedReadOnlyQueryClearsPreparedPhotoAndDoesNotSubmit() {
        pick();photoResult(syntheticPhoto())
        await {photoViews().any{it.contentDescription=="待提交的签到照片"}}
        photoButton("刷新学习通签到结果").performClick()
        await {photoViews().filterIsInstance<TextView>().any{it.text=="签到结果 · 成功"}}
        assertTrue(photoViews().filterIsInstance<TextView>().any{it.text==queryOutcome.message})
        assertEquals(1,queried.get());assertEquals(0,submitted.get())
        assertFalse(photoViews().any{it.contentDescription=="待提交的签到照片"})
        assertFalse(photoViews().filterIsInstance<Button>().any{it.text=="上传并签到"})
        assertNull(shadowOf(photoActivity).nextStartedActivity)
    }
    @Test fun unsignedQueryAfterUnknownSubmissionDoesNotEnableAnotherSubmission() {
        submissionOutcome=SignOutcome(false,"提交结果待核实",SignResultCategory.UNKNOWN)
        queryOutcome=SignOutcome(false,"学习通当前记录为未签到；查询不会重新提交。",SignResultCategory.NOT_SUBMITTED)
        pick();photoResult(syntheticPhoto())
        await {photoViews().any{it.contentDescription=="待提交的签到照片"}}
        photoButton("上传并签到").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await {photoViews().filterIsInstance<TextView>().any{it.text=="提交结果待核实"}}
        photoButton("刷新学习通签到结果").performClick()
        await {photoViews().filterIsInstance<TextView>().any{it.text.toString().startsWith("结果查询：学习通当前记录为未签到")}}
        assertEquals(1,queried.get());assertEquals(1,submitted.get())
        assertTrue(app.signing.ledger.attempted(identity,target))
        assertFalse(photoViews().filterIsInstance<Button>().any{it.text=="上传并签到"})
    }
    @Test fun cameraAndLibraryPermissionsCanStayDeniedWhileSelectedPhotoIsPreviewedAndRemoved() {
        shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA,android.Manifest.permission.READ_MEDIA_IMAGES,android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val before=app.cacheDir.listFiles().orEmpty().map{it.name}.toSet()
        pick();photoResult(syntheticPhoto())
        await {photoViews().any{it.contentDescription=="待提交的签到照片"}}
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,photoActivity.checkSelfPermission(android.Manifest.permission.CAMERA))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,photoActivity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(photoViews().filterIsInstance<TextView>().any{it.text.contains("无需摄像头")})
        photoButton("移除本次照片").performClick()
        assertFalse(photoViews().any{it.contentDescription=="待提交的签到照片"})
        assertFalse(photoViews().filterIsInstance<Button>().any{it.text=="上传并签到"})
        assertEquals(0,submitted.get())
        assertTrue(app.cacheDir.listFiles().orEmpty().none{it.name !in before && it.name.startsWith("sign-photo-")})
        assertNull(shadowOf(photoActivity).nextStartedActivity)
    }
    @Test fun switchingAccountDiscardsAnOutstandingPickerResult() {
        pick()
        app.prefs.edit().putString("identity",accountKey("other-account")).commit();panel.render()
        photoResult(syntheticPhoto());shadowOf(Looper.getMainLooper()).idle()
        assertFalse(photoViews().any{it.contentDescription=="待提交的签到照片"})
        assertEquals(0,submitted.get());assertFalse(text().contains("合成拍照签到"))
    }
    @Test fun presetPickerPreviewsSavesAndClearsWithoutSubmittingAndPhotoSwitchIsIndependent() {
        button("设置").performClick()
        button("预选 / 更换相册照片").performClick()
        val request=shadowOf(activity).nextStartedActivityForResult
        assertEquals(SigningPanel.PRESET_REQUEST,request.requestCode)
        assertEquals(android.provider.MediaStore.ACTION_PICK_IMAGES,request.intent.action)
        panel.onPresetResult(syntheticPhoto())
        await {ShadowAlertDialog.getLatestAlertDialog()?.isShowing==true}
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        preview(dialog.window!!.decorView,"预选照片-合成图片")
        assertEquals(0,submitted.get())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await {app.savedSignPhotos.file(identity)!=null}
        assertTrue(app.savedSignPhotos.file(identity)!!.exists())
        assertTrue(text().contains("预选照片已保存"));assertFalse(text().contains("正在缩小预选照片"))
        assertFalse(app.prefs.getBoolean("autoPhoto.$identity",false))
        views(panel).filterIsInstance<CheckBox>().single{it.text=="拍照签到自动提交预选照片"}.performClick()
        assertTrue(app.prefs.getBoolean("autoPhoto.$identity",false))
        assertFalse(app.prefs.getBoolean("autoNormal.$identity",false))
        preview(panel,"拍照自动提交-已开启")
        button("清除预选照片").performClick()
        assertNull(app.savedSignPhotos.file(identity));assertFalse(app.prefs.getBoolean("autoPhoto.$identity",true))
        assertEquals(0,submitted.get())
    }
}
