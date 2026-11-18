package cn.classbell.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
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
class LocationDemoTest {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(root:View,label:String)=views(root).filterIsInstance<Button>().single{it.text==label}
    private fun result(root:View,label:String)=views(root).filterIsInstance<TextView>().any{it.visibility==View.VISIBLE && it.text.startsWith(label)}
    private fun layout(view:View,width:Int=390,height:Int=844){shadowOf(Looper.getMainLooper()).idle();view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)}
    private fun preview(view:View,name:String){
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);view.draw(canvas)
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun manualSelectionHandlesBothRangesWithoutAnyAccountOrStoredAttendance() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        app.prefs.edit().clear().commit();val before=app.prefs.all.toMap()
        val controller=Robolectric.buildActivity(LocationDemoActivity::class.java).setup();val root=controller.get().window.decorView
        try {
            layout(root);assertFalse(button(root,"模拟确认选点").isEnabled)
            val map=views(root).filterIsInstance<DemoCampusMap>().single()
            val x=map.width/2f;val y=map.height/2f
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP))MotionEvent.obtain(0,1,action,x,y,0).also{map.onTouchEvent(it);it.recycle()}
            assertNotNull(map.selected);assertTrue(map.selected!!.inside)
            button(root,"模拟确认选点").performClick();layout(root)
            val feedback=views(root).filterIsInstance<TextView>().single{it.text.startsWith("模拟通过")}
            val position=IntArray(2);feedback.getLocationOnScreen(position)
            assertTrue(position[1]>=0 && position[1]+feedback.height<=root.height)
            assertTrue(result(root,"模拟通过"));preview(root,"位置模拟-手动选点通过")
            button(root,"试试范围外").performClick();assertFalse(result(root,"模拟通过"));assertTrue(button(root,"模拟确认选点").isEnabled)
            button(root,"模拟确认选点").performClick();layout(root)
            assertTrue(result(root,"模拟未通过"));preview(root,"位置模拟-范围外")
            assertEquals(before,app.prefs.all)
            button(root,"重新选择").performClick()
            assertNull(map.selected);assertFalse(button(root,"模拟确认选点").isEnabled);assertFalse(result(root,"模拟未通过"))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun selectedPointAndLocalResultSurviveRotation() {
        val controller=Robolectric.buildActivity(LocationDemoActivity::class.java).setup()
        try {
            val root=controller.get().window.decorView;button(root,"试试范围内").performClick();button(root,"模拟确认选点").performClick()
            controller.recreate()
            assertTrue(result(controller.get().window.decorView,"模拟通过"))
            assertFalse(button(controller.get().window.decorView,"模拟确认选点").isEnabled)
        }finally{controller.pause().stop().destroy()}
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi")
    fun narrowLargeTextLeavesThePracticeActionAccessible() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(app.resources.configuration.apply{fontScale=1.5f},app.resources.displayMetrics)
        val controller=Robolectric.buildActivity(LocationDemoActivity::class.java).setup()
        try {
            val root=controller.get().window.decorView;layout(root,320,720)
            val action=button(root,"模拟确认选点");val position=IntArray(2);action.getLocationOnScreen(position)
            assertTrue(action.height>=48);assertTrue(position[1]>=0 && position[1]+action.height<=720)
            preview(root,"位置模拟-窄屏大字号")
        }finally{controller.pause().stop().destroy()}
    }
}
