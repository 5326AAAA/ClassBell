package cn.classbell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QrScanTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val text="https://mobilelearn.chaoxing.com/widget/sign/e?id=1700000000000&c=123456&enc=0123456789abcdef0123456789abcdef"
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(root:View,name:String)=views(root).filterIsInstance<Button>().single{it.text==name}
    private fun setup(){
        app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,accountKey("qr-fixture"),listOf(Course("101","201","合成课程")))
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA,false)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH,false)
    }
    private fun materialPage()=Intent(app,QrScanActivity::class.java).putExtra("expectedId","1700000000000")
    private fun scanner(root:View)=views(root).filterIsInstance<BarcodeView>().single()
    private fun fakeCamera(root:View)=Shadow.extract<FakeBarcodeView>(scanner(root))
    private fun decoded(raw:String)=BarcodeResult(Result(raw,null,emptyArray(),BarcodeFormat.QR_CODE),null)
    private fun denyCamera(activity:QrScanActivity,permanent:Boolean){
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA,!permanent)
        // Android's permission controller is itself an activity; consume that launch before
        // asserting that the app did not navigate to settings or another application.
        val permissionUi=shadowOf(activity).nextStartedActivity
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",permissionUi.action)
        activity.onRequestPermissionsResult(QrScanActivity.CAMERA,arrayOf(Manifest.permission.CAMERA),intArrayOf(PackageManager.PERMISSION_DENIED))
    }
    private fun savedBytes(state:Bundle):ByteArray {
        val parcel=Parcel.obtain()
        return try{parcel.writeBundle(state);parcel.marshall()}finally{parcel.recycle()}
    }
    private fun contains(bytes:ByteArray,value:String)=listOf(Charsets.UTF_8,Charsets.UTF_16LE).any{charset->
        val needle=value.toByteArray(charset)
        (0..bytes.size-needle.size).any{offset->needle.indices.all{bytes[offset+it]==needle[it]}}
    }
    private fun preview(root:View,name:String){
        shadowOf(Looper.getMainLooper()).idle();root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile;val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun encodedSyntheticImageDecodesLocallyAndReturnsFreshMaterialWithoutAConfirmationPage() {
        setup();val bits=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,600,600)
        val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        for(y in 0 until 600)for(x in 0 until 600)bitmap.setPixel(x,y,if(bits[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val decoded=try{QrImage.decode(bitmap)}finally{bitmap.recycle()};assertEquals(text,decoded)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            activity.accept("https://example.org/not-a-sign")
            assertFalse(activity.isFinishing)
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.startsWith("未识别为支持")})
            button(root,"从相册识别二维码").performClick()
            val picker=shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,picker.intent.action)
            activity.accept(decoded)
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
            assertEquals(text,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
            assertTrue(shadowOf(activity).resultIntent.hasExtra(QrScanActivity.CAPTURED_AT))
            assertFalse(views(root).filterIsInstance<TextView>().any{it.text.contains("0123456789abcdef")})
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun galleryOpensACropPreviewBeforeRecognitionAndReturnsOnlyStatusInTestMode() {
        setup()
        val host=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val page=QrScanActivity.previewIntent(host.get(),QrScanActivity.PreviewInput.GALLERY)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup()
        val activity=controller.get()
        val bits=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,400,400)
        val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        val output=java.io.ByteArrayOutputStream()
        try {
            for(y in 0 until 400)for(x in 0 until 400)image.setPixel(x,y,if(bits[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            image.compress(Bitmap.CompressFormat.PNG,100,output)
            val uri=android.net.Uri.parse("content://fixture/gallery-code")
            shadowOf(activity.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(output.toByteArray()))
            val launch=shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(launch.intent,android.app.Activity.RESULT_OK,Intent().setData(uri))
            fun await(condition:()->Boolean) {
                val end=System.nanoTime()+5_000_000_000L
                while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)}
                fail("Gallery operation did not finish")
            }
            await{org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing==true}
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("原图识别",dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).text)
            assertFalse(activity.isFinishing)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            await{activity.isFinishing}
            assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
            assertNull(shadowOf(activity).resultIntent)
        }finally{image.recycle();controller.pause().stop().destroy();host.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun accountChangeClosesTheScannerAndDiscardedResultsCannotOpenAPage() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get()
        try {
            app.prefs.edit().putString("identity",accountKey("other-qr-fixture")).commit();app.notifyUi();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            activity.accept(text)
            assertNull(shadowOf(activity).resultIntent)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun materialPickerBindsActivityAndReturnsOnlyAfterAMatchingCode() {
        setup()
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","301")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup();val activity=controller.get()
        try {
            activity.accept(text);assertFalse(activity.isFinishing)
            val matching=text.replace("1700000000000","301")
            activity.accept(matching)
            assertTrue(activity.isFinishing);assertEquals(android.app.Activity.RESULT_OK,shadowOf(activity).resultCode)
            assertEquals(matching,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun cameraPermissionDenialExplainsAlternativesWithoutStartingCameraOrSettings() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            val camera=fakeCamera(root)
            assertEquals(0,camera.resumeCount)
            button(root,"兼容扫码（ZXing）").performClick()
            val request=shadowOf(activity).lastRequestedPermission
            assertEquals(QrScanActivity.CAMERA,request.requestCode)
            assertArrayEquals(arrayOf(Manifest.permission.CAMERA),request.requestedPermissions)
            denyCamera(activity,permanent=false)
            assertEquals(0,camera.resumeCount);assertTrue(camera.callbacks.isEmpty())
            assertEquals(View.GONE,scanner(root).visibility)
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("不会启动摄像头") && it.text.contains("从相册识别")})
            assertEquals(View.GONE,button(root,"打开应用设置").visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun permanentDenialOpensOnlyThisAppsSettingsAfterAnExplicitClickAndThenResumes() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            button(root,"兼容扫码（ZXing）").performClick();denyCamera(activity,permanent=true)
            val settings=button(root,"打开应用设置")
            assertEquals(View.VISIBLE,settings.visibility);assertNull(shadowOf(activity).nextStartedActivity)
            settings.performClick()
            val launched=shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,launched.action)
            assertEquals("package:${activity.packageName}",launched.data.toString())
            controller.pause();shadowOf(app).grantPermissions(Manifest.permission.CAMERA);controller.resume()
            val camera=fakeCamera(root)
            assertEquals(1,camera.resumeCount);assertEquals(1,camera.callbacks.size)
            assertEquals(View.VISIBLE,scanner(root).visibility);assertEquals(View.GONE,settings.visibility)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun grantingPermissionWithoutACameraRequestDoesNotStartScanningOnResume() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            controller.pause();shadowOf(app).grantPermissions(Manifest.permission.CAMERA);controller.resume()
            activity.onRequestPermissionsResult(QrScanActivity.CAMERA,arrayOf(Manifest.permission.CAMERA),intArrayOf(PackageManager.PERMISSION_GRANTED))
            assertEquals(0,fakeCamera(root).resumeCount);assertTrue(fakeCamera(root).callbacks.isEmpty())
            assertEquals(View.GONE,scanner(root).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun choosingAnImageCancelsPermissionIntentAndLatePermissionResultsCannotRestartCamera() {
        setup();val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            button(root,"兼容扫码（ZXing）").performClick();denyCamera(activity,permanent=true)
            button(root,"从相册识别二维码").performClick()
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(QrScanActivity.IMAGE,request.requestCode)
            controller.pause();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            activity.onRequestPermissionsResult(QrScanActivity.CAMERA,arrayOf(Manifest.permission.CAMERA),intArrayOf(PackageManager.PERMISSION_GRANTED))
            controller.resume()
            assertEquals(0,fakeCamera(root).resumeCount);assertTrue(fakeCamera(root).callbacks.isEmpty())
            assertEquals(View.GONE,scanner(root).visibility);assertEquals(View.GONE,button(root,"打开应用设置").visibility)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun pauseInvalidatesOldDecodeCallbackAndResumeInstallsANewSingleScan() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","1700000000000")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            button(root,"兼容扫码（ZXing）").performClick();val camera=fakeCamera(root);val stale=camera.callbacks.single()
            controller.pause();stale.barcodeResult(decoded(text))
            assertFalse(activity.isFinishing);assertNull(shadowOf(activity).resultIntent)
            controller.resume();assertEquals(2,camera.callbacks.size);assertEquals(2,camera.resumeCount)
            stale.barcodeResult(decoded(text));assertFalse(activity.isFinishing)
            camera.callbacks.last().barcodeResult(decoded(text))
            assertTrue(activity.isFinishing);assertEquals(text,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun cancelledScanRejectsOldCallbackAndAccountInvalidationRejectsNewScanAndMaterialReturn() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","1700000000000")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            button(root,"兼容扫码（ZXing）").performClick();val camera=fakeCamera(root);val cancelled=camera.callbacks.single()
            button(root,"从相册识别二维码").performClick()
            assertEquals(QrScanActivity.IMAGE,shadowOf(activity).nextStartedActivityForResult.requestCode)
            shadowOf(activity).clearNextStartedActivities()
            cancelled.barcodeResult(decoded(text));assertFalse(activity.isFinishing);assertNull(shadowOf(activity).resultIntent)
            button(root,"兼容扫码（ZXing）").performClick();val invalidated=camera.callbacks.last();val resumes=camera.resumeCount
            app.prefs.edit().putString("identity",accountKey("other-qr-fixture")).commit()
            val stateBefore=views(root).filterIsInstance<TextView>().map{it.text.toString()}
            camera.fail();assertEquals(stateBefore,views(root).filterIsInstance<TextView>().map{it.text.toString()})
            invalidated.barcodeResult(decoded(text));activity.accept(text);button(root,"兼容扫码（ZXing）").performClick()
            assertEquals(resumes,camera.resumeCount);assertNull(shadowOf(activity).resultIntent)
            app.notifyUi();shadowOf(Looper.getMainLooper()).idle();assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun torchIsAvailableOnlyDuringPreviewAndIsResetOnPauseAndSuccessfulDecode() {
        setup();shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH,true)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            val torch=button(root,"打开手电筒");assertEquals(View.VISIBLE,torch.visibility);assertFalse(torch.isEnabled)
            button(root,"兼容扫码（ZXing）").performClick();val camera=fakeCamera(root)
            assertTrue(torch.isEnabled);torch.performClick();assertEquals("关闭手电筒",torch.text);assertEquals(true,camera.torchChanges.last())
            controller.pause();assertFalse(torch.isEnabled);assertEquals("打开手电筒",torch.text);assertEquals(false,camera.torchChanges.last())
            controller.resume();assertTrue(torch.isEnabled);assertEquals("打开手电筒",torch.text)
            torch.performClick();camera.callbacks.last().barcodeResult(decoded(text))
            assertFalse(torch.isEnabled);assertEquals(false,camera.torchChanges.last());assertEquals(View.GONE,scanner(root).visibility)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun unsupportedTorchStaysHiddenAndLateCameraErrorsAfterDestroyDoNotChangeViews() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val root=activity.window.decorView
        try {
            val torch=button(root,"打开手电筒");assertEquals(View.GONE,torch.visibility);assertFalse(torch.isEnabled)
            button(root,"兼容扫码（ZXing）").performClick();val camera=fakeCamera(root)
            assertEquals(View.GONE,torch.visibility);assertFalse(torch.isEnabled)
            controller.pause().stop().destroy()
            val snapshot=views(root).filterIsInstance<TextView>().map{it.text.toString()}
            val pauses=camera.pauseCount;camera.fail()
            assertEquals(snapshot,views(root).filterIsInstance<TextView>().map{it.text.toString()});assertEquals(pauses,camera.pauseCount)
        }finally{if(!activity.isDestroyed)controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun recreationDropsDecodedMaterialAndCameraIntentWithoutPersistingRawQrOrSecret() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get()
        val saved=Bundle()
        try {
            activity.accept(text);assertTrue(activity.isFinishing)
            controller.pause().saveInstanceState(saved).stop().destroy()
            val bytes=savedBytes(saved)
            assertFalse(contains(bytes,text));assertFalse(contains(bytes,"0123456789abcdef0123456789abcdef"));assertFalse(saved.containsKey(QrScanActivity.MATERIAL))
            val restored=Robolectric.buildActivity(QrScanActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible()
            try {
                val page=restored.get();val root=page.window.decorView
                assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("请重新扫描")})
                assertFalse(views(root).filterIsInstance<Button>().any{it.text=="核对课程并继续"})
                assertEquals(0,fakeCamera(root).resumeCount);assertEquals(View.GONE,scanner(root).visibility)
                assertNull(shadowOf(page).resultIntent);assertNull(shadowOf(page).nextStartedActivity)
            }finally{restored.pause().stop().destroy()}
        }finally{if(!activity.isDestroyed)controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun recreationNeverRestoresAnActiveCameraRequest() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup();val activity=controller.get();val saved=Bundle()
        try {
            button(activity.window.decorView,"兼容扫码（ZXing）").performClick();assertEquals(1,fakeCamera(activity.window.decorView).resumeCount)
            controller.pause().saveInstanceState(saved).stop().destroy()
            val restored=Robolectric.buildActivity(QrScanActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible()
            try {
                val root=restored.get().window.decorView
                assertEquals(0,fakeCamera(root).resumeCount);assertTrue(fakeCamera(root).callbacks.isEmpty());assertEquals(View.GONE,scanner(root).visibility)
            }finally{restored.pause().stop().destroy()}
        }finally{if(!activity.isDestroyed)controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun liveScannerIsLocalAndRejectsOldOrForeignResults() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup()
        val activity=controller.get();val root=activity.window.decorView
        val live=FakeLivePreview(activity)
        activity.livePreviewFactory={live}
        try {
            assertTrue(live.sessions.isEmpty())
            button(root,"摄像头扫码").performClick()
            val first=live.sessions.single()
            first.code("https://example.org/foreign")
            assertFalse(activity.isFinishing)
            controller.pause();first.code(text);controller.resume()
            assertEquals(2,live.sessions.size)
            first.failed();first.code(text)
            assertFalse(activity.isFinishing)
            val second=live.sessions.last();second.ready(true)
            val torch=button(root,"打开手电筒");assertTrue(torch.isEnabled)
            torch.performClick();assertEquals(listOf(true),live.torch)
            live.torchFailure?.invoke();assertEquals("打开手电筒",torch.text)
            second.code(text)
            assertFalse(torch.isEnabled)
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNotNull(shadowOf(activity).resultIntent)
        } finally {
            controller.pause().stop().destroy();app.prefs.edit().clear().commit()
        }
        assertTrue(live.closed)
    }

    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun failedLiveScannerLeavesCompatibilityModeAvailableWithoutOpeningAnotherApp() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup()
        val activity=controller.get();val root=activity.window.decorView
        val live=FakeLivePreview(activity);activity.livePreviewFactory={live}
        try {
            button(root,"摄像头扫码").performClick()
            val failed=live.sessions.single();failed.failed()
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("实时相机暂不可用")})
            assertNull(shadowOf(activity).nextStartedActivity)
            button(root,"兼容扫码（ZXing）").performClick()
            failed.code(text)
            assertFalse(activity.isFinishing)
            fakeCamera(root).callbacks.single().barcodeResult(decoded(text))
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }

    @Test fun stopScanHidesTheViewfinderAndDoesNotRestartOnResumeOrAcceptAnOldFrame() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,materialPage()).setup()
        val activity=controller.get();val root=activity.window.decorView
        val live=FakeLivePreview(activity);activity.livePreviewFactory={live}
        try {
            val stop=button(root,"停止扫码")
            assertEquals(View.GONE,stop.visibility)
            button(root,"摄像头扫码").performClick()
            val session=live.sessions.single();session.ready(false)
            assertEquals(View.VISIBLE,stop.visibility)
            session.code("https://example.org/unrelated")
            val hint=views(root).filterIsInstance<TextView>().single{it.text.toString().contains("请继续扫描")}
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,hint.accessibilityLiveRegion)
            preview(root,"扫码-取景与过滤提示")
            stop.performClick();session.code(text)
            assertFalse(live.active);assertEquals(View.GONE,stop.visibility)
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.toString().contains("摄像头已关闭")})
            controller.pause().resume()
            assertEquals(1,live.sessions.size)
            assertFalse(views(root).filterIsInstance<Button>().any{it.text=="核对课程并继续"})
            button(root,"摄像头扫码").performClick()
            assertEquals(2,live.sessions.size)
            assertTrue(live.active)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }

    @Test fun livePreviewSkipsForeignAndWrongActivityAndSelectsOneMatchingCodeFromTheFrame() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","301")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup()
        val activity=controller.get();val root=activity.window.decorView
        val live=FakeLivePreview(activity);activity.livePreviewFactory={live}
        val matching=text.replace("1700000000000","301")
        try {
            button(root,"摄像头扫码").performClick()
            val session=live.sessions.single();session.ready(false)
            session.code("https://example.org/foreign")
            repeat(3){session.code(text)}
            assertTrue(live.active);assertFalse(activity.isFinishing)
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("与所选签到不一致")})
            session.codes(listOf(matching,matching.replace("0123456789abcdef","fedcba9876543210")))
            assertTrue(live.active);assertFalse(activity.isFinishing)
            assertTrue(views(root).filterIsInstance<TextView>().any{it.text.contains("有多个")})
            session.codes(listOf(text,"https://example.org/foreign",matching,matching))
            assertFalse(live.active);assertTrue(activity.isFinishing)
            assertEquals(matching,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
            session.code(text)
            assertEquals(matching,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }

    @Test @Config(shadows=[FakeCameraPreview::class,FakeBarcodeView::class])
    fun compatibilityCameraKeepsScanningUntilMatchingActivity() {
        setup();shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val page=Intent(app,QrScanActivity::class.java).putExtra("expectedId","301")
        val controller=Robolectric.buildActivity(QrScanActivity::class.java,page).setup()
        val activity=controller.get();val root=activity.window.decorView
        val matching=text.replace("1700000000000","301")
        try {
            button(root,"兼容扫码（ZXing）").performClick()
            val camera=fakeCamera(root);val callback=camera.callbacks.single()
            callback.barcodeResult(decoded("https://example.org/foreign"))
            callback.barcodeResult(decoded(text))
            assertFalse(activity.isFinishing);assertEquals(View.VISIBLE,scanner(root).visibility)
            assertEquals(0,camera.pauseCount)
            callback.barcodeResult(decoded(matching))
            assertTrue(activity.isFinishing);assertEquals(1,camera.pauseCount)
            assertEquals(matching,shadowOf(activity).resultIntent.getStringExtra(QrScanActivity.MATERIAL))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }

    private class FakeLivePreview(context:android.content.Context):LiveQrPreview {
        data class Session(val ready:(Boolean)->Unit,val codes:(List<String>)->Unit,val failed:()->Unit){
            fun code(raw:String)=codes(listOf(raw))
        }
        override val view=View(context)
        val sessions=mutableListOf<Session>()
        val torch=mutableListOf<Boolean>()
        var torchFailure:(()->Unit)?=null
        var closed=false
        var active=false
        override fun start(onReady:(Boolean)->Unit,onCodes:(List<String>)->Unit,onError:()->Unit){active=true;sessions.add(Session(onReady,onCodes,onError))}
        override fun setTorch(enabled:Boolean,onFailure:()->Unit){torch.add(enabled);torchFailure=onFailure}
        override fun stop(){active=false}
        override fun close(){closed=true}
    }

    @Implements(value=CameraPreview::class,isInAndroidSdk=false)
    open class FakeCameraPreview:org.robolectric.shadows.ShadowViewGroup() {
        var resumeCount=0
        var pauseCount=0
        val torchChanges=mutableListOf<Boolean>()
        private val listeners=mutableListOf<CameraPreview.StateListener>()
        @Implementation fun addStateListener(listener:CameraPreview.StateListener){listeners.add(listener)}
        @Implementation fun resume(){resumeCount++;listeners.forEach{it.previewStarted()}}
        @Implementation fun pause(){pauseCount++;listeners.forEach{it.previewStopped()}}
        @Implementation fun setTorch(enabled:Boolean){torchChanges.add(enabled)}
        fun fail(){listeners.forEach{it.cameraError(IllegalStateException("合成相机错误"))}}
    }
    @Implements(value=BarcodeView::class,isInAndroidSdk=false)
    class FakeBarcodeView:FakeCameraPreview() {
        val callbacks=mutableListOf<BarcodeCallback>()
        @Implementation fun decodeContinuous(callback:BarcodeCallback){callbacks.add(callback)}
        @Implementation fun stopDecoding(){}
    }
}
