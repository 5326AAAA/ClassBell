package cn.classbell.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualLocationScreensTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(view:View,text:String)=views(view).filterIsInstance<Button>().single{it.text==text}
    private fun setup(){app.prefs.edit().clear().commit();app.courseMemory.activate(app.prefs,accountKey("manual-a"),listOf(Course("101","201","合成课程")))}
    private fun preview(root:View,name:String){
        shadowOf(Looper.getMainLooper()).idle();root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val cwd=File(System.getProperty("user.dir").orEmpty());val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile;val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas);File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun chosenCoordinatesArePreviewedAndReturnedOnlyAfterConfirmation() {
        setup();val controller=Robolectric.buildActivity(LocationPickerActivity::class.java,Intent(app,LocationPickerActivity::class.java).putExtra("return",true)).setup();val activity=controller.get()
        try {
            activity.receiveMapPoint(1.3,103.8)
            activity.receiveMapPoint(Double.NaN,200.0)
            preview(activity.window.decorView,"手动位置-坐标待确认")
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains("纬度 1.3")})
            assertFalse(activity.isFinishing)
            button(activity.window.decorView,"确认使用此位置").performClick()
            assertTrue(activity.isFinishing);assertEquals(1.3,shadowOf(activity).resultIntent.getDoubleExtra("latitude",0.0),0.00001)
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun initialCampusViewDoesNotSelectOrReturnAnyPoint(){
        setup();val controller=Robolectric.buildActivity(LocationPickerActivity::class.java,Intent(app,LocationPickerActivity::class.java).putExtra("return",true)).setup()
        try{
            val activity=controller.get();assertFalse(button(activity.window.decorView,"确认使用此位置").isEnabled)
            preview(activity.window.decorView,"地图选点-默认校区视野")
            button(activity.window.decorView,"回到校区").performClick()
            assertFalse(button(activity.window.decorView,"确认使用此位置").isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
            val map=views(activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
            activity.receiveMapPoint(1.3,103.8)
            shadowOf(map).webViewClient.onPageFinished(map,"https://appassets.androidplatform.net/classbell-map/")
            assertTrue(shadowOf(map).lastEvaluatedJavascript.contains("1.3,103.8"))
            assertFalse(activity.isFinishing)
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun gpsCanSeedThePointButLateGpsCannotOverwriteManualAdjustment(){
        setup();shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val controller=Robolectric.buildActivity(LocationPickerActivity::class.java,Intent(app,LocationPickerActivity::class.java).putExtra("return",true)).setup()
        try{
            val activity=controller.get();var result:((LocationFix?)->Unit)?=null;activity.locateSource={result=it}
            button(activity.window.decorView,"用手机定位选点").performClick()
            result!!(LocationFix(39.8,116.3,10f,android.os.SystemClock.elapsedRealtime()))
            button(activity.window.decorView,"用手机定位选点").performClick();val stale=checkNotNull(result)
            activity.receiveMapPoint(39.9,116.4)
            stale(LocationFix(39.7,116.2,10f,android.os.SystemClock.elapsedRealtime()))
            button(activity.window.decorView,"调整经纬度").performClick()
            preview(activity.window.decorView,"地图选点-定位后调整")
            button(activity.window.decorView,"确认使用此位置").performClick()
            assertEquals(ManualLocation(39.9,116.4),ManualLocation.fromIntent(shadowOf(activity).resultIntent))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun leavingPickerIgnoresAnOutstandingGpsResult(){
        setup();shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val controller=Robolectric.buildActivity(LocationPickerActivity::class.java).setup()
        try{
            val activity=controller.get();var result:((LocationFix?)->Unit)?=null;activity.locateSource={result=it}
            button(activity.window.decorView,"用手机定位选点").performClick();controller.pause().stop()
            result!!(LocationFix(39.8,116.3,10f,android.os.SystemClock.elapsedRealtime()))
            assertFalse(button(activity.window.decorView,"预览所选位置").isEnabled)
        }finally{controller.destroy();app.prefs.edit().clear().commit()}
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") fun largeTextKeepsConfirmationOutsideTheScrollArea(){
        setup();@Suppress("DEPRECATION") app.resources.updateConfiguration(app.resources.configuration.apply{fontScale=1.5f},app.resources.displayMetrics)
        val controller=Robolectric.buildActivity(LocationPickerActivity::class.java,Intent(app,LocationPickerActivity::class.java).putExtra("return",true)).setup()
        try{
            val root=controller.get().window.decorView;root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,320,720)
            val confirm=button(root,"确认使用此位置");val xy=IntArray(2);confirm.getLocationOnScreen(xy)
            assertTrue(xy[1]>=0 && xy[1]+confirm.height<=720);assertTrue(confirm.height>=48)
            assertFalse(generateSequence(confirm.parent){it.parent}.any{it is android.widget.ScrollView})
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun unavailableLocationSectionRetainsPreviouslySavedPreset() {
        setup();val values=mutableMapOf<String,String>();app.locationRepository=LocationPresets({values[it]},{k,v->values[k]=v},{values.remove(it);Unit})
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get();val panel=views(activity.window.decorView).filterIsInstance<SigningPanel>().single()
        try {
            val saved=ManualLocation(1.3,103.8,"合成地点")
            app.locationPresets.save(accountKey("manual-a"),saved)
            button(panel,"设置").performClick()
            assertTrue(views(panel).filterIsInstance<android.widget.TextView>().any{it.text=="位置签到 · 不可用"})
            assertTrue(views(panel).filterIsInstance<Button>().none{it.text=="预选 / 更换位置"})
            app.courseMemory.activate(app.prefs,accountKey("manual-b"),listOf(Course("101","201","合成课程")));panel.render()
            assertNull(app.locationPresets.get(accountKey("manual-b")))
            assertEquals(saved,app.locationPresets.get(accountKey("manual-a")))
        }finally{controller.pause().stop().destroy();app.locationRepository=null;app.prefs.edit().clear().commit()}
    }
}
