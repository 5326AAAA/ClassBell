package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.Cookie
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real system album and Android image preparation; account, ledger and sign server are isolated. */
@RunWith(AndroidJUnit4::class)
class DevicePhotoAlbumTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BellApp
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    private fun text(activity:Activity):String {var value="";main{value=views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n"){it.text}};return value}
    private fun click(activity:Activity,label:String)=main{
        val button=views(activity.window.decorView).filterIsInstance<Button>().single{it.text.toString()==label}
        assertTrue(button.isEnabled);button.performClick()
    }
    private fun await(label:String,timeout:Long=15_000,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)}
        fail(label)
    }

    @Test fun realSystemAlbumPreviewsAndSubmitsOnlyTheChosenPhotoToASimulatedAccount() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("albumPhotoTest")=="true")
        fun accountSettings()=app.prefs.all.filterKeys{key->
            key in setOf("identity","loggedIn","courses","selected","monitorEnabled") ||
                listOf("sign.","account.","homework.","autoNormal.","autoPhoto.").any{key.startsWith(it)}
        }
        val original=accountSettings()
        val course=Course("99101","99201","USB相册模拟课程")
        val sign=SignActivity("99301","USB相册模拟签到",course,otherId=0)
        var accountJson:String?=null
        val repository=BoundAccounts({accountJson},{accountJson=it})
        val account=repository.bind("USB模拟账号",listOf(Cookie.Builder().name("_uid").value("99001")
            .domain("chaoxing.com").path("/").secure().build()),listOf(course))
        val prefsName="codex-album-${UUID.randomUUID()}"
        val ledger=SignLedger(context.getSharedPreferences(prefsName,Context.MODE_PRIVATE))
        val sends=AtomicInteger()
        var submittedPath:String?=null
        val folder=File(context.filesDir,"codex-debug/photo-album-test").apply{mkdirs()}
        val report=JSONObject().put("version",BuildConfig.VERSION_NAME).put("server","simulated SignSource; no real sign request")
        fun status(phase:String){report.put("phase",phase);File(folder,"result.json").writeText(report.toString(2))}
        var uri:Uri?=null
        var activity:SignActionActivity?=null
        val callback=ActivityLifecycleCallback { page,stage->
            if(stage==Stage.PRE_ON_CREATE && page is SignActionActivity){
                page.boundAccountRepository=repository;page.ledgerRepository=ledger;page.requirementTrace={}
                page.sourceFactory={guard->object:SignSource {
                    override fun activities(course:Course)=listOf(sign)
                    override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.PHOTO,"USB模拟拍照要求")}
                    override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                        guard();val file=checkNotNull(photo);assertTrue(file.isFile)
                        val image=checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
                        try {
                            assertEquals(960,image.width);assertEquals(640,image.height)
                            val pixel=image.getPixel(100,100)
                            assertTrue("The selected file was not the synthetic green test image",Color.green(pixel)>130 && Color.red(pixel)<70)
                        }finally{image.recycle()}
                        assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator))
                        beforeCommit();submittedPath=file.absolutePath;sends.incrementAndGet()
                        return SignOutcome(true,"USB模拟相册签到已确认",photoState=PhotoTransferState.CONFIRMED)
                    }
                    override fun cancel(){}
                }}
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        try {
            val name="ClassBell-USB-Album-${System.currentTimeMillis()}.jpg"
            uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
                put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClassBellTest");put(MediaStore.Images.Media.IS_PENDING,1)
            }) ?: error("Cannot create test album image")
            val bitmap=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(20,180,90))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,180,30)}
                canvas.drawRect(480f,0f,960f,640f,paint);paint.color=Color.WHITE;paint.textSize=96f
                canvas.drawText("USB TEST",90f,380f,paint)
                context.contentResolver.openOutputStream(uri).use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it!!))}
            }finally{bitmap.recycle()}
            context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            val sourceHash=context.contentResolver.openInputStream(uri)!!.use{MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
            report.put("fixtureName",name);status("starting")
            activity=instrumentation.startActivitySync(Intent(context,SignActionActivity::class.java)
                .putExtra("courseKey",course.key).putExtra("activeId",sign.id).putExtra("title",sign.title).putExtra("otherId",0)
                .putExtra("boundAccountId",account.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SignActionActivity
            val page=activity
            await("Photo requirement page did not open"){"从相册选择本次照片" in text(page)}
            click(page,"从相册选择本次照片");status("awaiting_system_album_selection")
            await("Select the synthetic USB TEST image in the real system album",180_000){"已在本机处理并预览" in text(page)}
            main{assertTrue(views(page.window.decorView).filterIsInstance<ImageView>().any{it.contentDescription=="待提交的签到照片" && it.drawable!=null})}
            assertEquals(0,sends.get());report.put("previewBeforeUpload",true);status("preview_ready")
            click(page,"上传并签到")
            assertEquals(0,sends.get())
            main {
                val field=SignActionActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true}
                val dialog=field.get(page) as AlertDialog
                assertTrue(dialog.isShowing);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await("The simulated sign did not finish"){"USB模拟相册签到已确认" in text(page)}
            assertEquals(1,sends.get());assertTrue(ledger.attempted(account.id,sign))
            assertFalse(File(checkNotNull(submittedPath)).exists())
            val afterHash=context.contentResolver.openInputStream(uri)!!.use{MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
            assertArrayEquals(sourceHash,afterHash)
            report.put("confirmedSubmissions",1).put("privateCopyCleaned",true).put("sourceImageUnchanged",true)
                .put("realSystemPicker",true).put("verified",true)
            instrumentation.uiAutomation.takeScreenshot()?.let{shot->try{File(folder,"screen.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()}}
            status("verified")
        }finally {
            activity?.let{page->main{page.finish()};await("Photo page did not close"){page.isDestroyed}}
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
            uri?.let{context.contentResolver.delete(it,null,null)}
            context.deleteSharedPreferences(prefsName)
            assertTrue("Synthetic test changed real account settings",original==accountSettings())
            report.put("realSettingsUnchanged",true);File(folder,"result.json").writeText(report.toString(2))
            println("DEVICE_PHOTO_ALBUM "+report)
        }
    }
}
