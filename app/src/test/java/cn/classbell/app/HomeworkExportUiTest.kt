package cn.classbell.app

import android.content.ClipboardManager
import android.content.ClipData
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeworkExportUiTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val url="https://mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestion?workId=1"
    private val item=HomeworkItem("1","合成作业","合成数学课程","101:201","未提交","",null,1,false,url)
    private val html="""<div class='questionLi' typename='单选题'><h3 class='mark_name'>第一题的题干<img src='https://p.ananas.chaoxing.com/one.png'></h3><div class='answerBg'><span class='num_option'>A.</span><div class='answer_p'>选项甲</div></div></div><div class='questionLi' typename='简答题'><h3 class='mark_name'>第二题的题干<img src='https://p.ananas.chaoxing.com/two.png'></h3></div>"""
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun await(condition:()->Boolean){val end=System.nanoTime()+8_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)};fail("Export UI did not finish")}
    private fun intent(owner:String="export-owner")=Intent(app,HomeworkExportActivity::class.java).putExtra("owner",owner).putExtra("homework",item.json().toString())
    private fun capture(root:View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
        val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
        val folder=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        File(folder,"作业题目-复制与题图.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun readingDoesNotFetchImagesAndWholeOrSingleCopyAndSaveAreExplicit() {
        app.prefs.edit().putString("identity","export-owner").putBoolean("loggedIn",true).commit()
        val reads=AtomicInteger();val saves=mutableListOf<List<String>>()
        val controller=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        controller.get().detailLoader={reads.incrementAndGet();HomeworkDetailPage(html,url)}
        controller.get().imageSaveAction={urls,_,progress->synchronized(saves){saves+=urls.toList()};progress(urls.size,urls.size);HomeworkImageSaveResult(urls.size,urls.size,emptyList())}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(label:String)=views(root).filterIsInstance<Button>().single{it.text==label}
        val clipboard=app.getSystemService(ClipboardManager::class.java)
        try {
            await{text().contains("2 道题 · 2 张题图")}
            assertEquals(1,reads.get());assertTrue(saves.isEmpty())
            capture(root)
            button("复制全部题目文字").performClick()
            val all=clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(all.contains("第一题的题干"));assertTrue(all.contains("第二题的题干"));assertTrue(all.contains("A. 选项甲"))
            assertFalse(all.contains("https:"));assertTrue(all.contains("[图片 2]"));assertTrue(saves.isEmpty())
            views(root).filterIsInstance<Button>().filter{it.text=="复制本题文字"}[1].performClick()
            val one=clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertFalse(one.contains("第一题"));assertTrue(one.contains("第二题的题干[图片 1]"))
            views(root).filterIsInstance<Button>().filter{it.text=="保存本题图片（1）"}[1].performClick()
            await{text().contains("保存完成：1 / 1")}
            assertEquals(listOf("https://p.ananas.chaoxing.com/two.png"),saves.single())
            button("保存全部题图到相册（2 张）").performClick();await{saves.size==2 && text().contains("保存完成：2 / 2")}
            assertEquals(listOf("https://p.ananas.chaoxing.com/one.png","https://p.ananas.chaoxing.com/two.png"),saves[1])
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun changingAccountPreventsCopyAndImageSaveAndStaleIntentCannotRead() {
        app.prefs.edit().putString("identity","export-owner").putBoolean("loggedIn",true).commit()
        val controller=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        controller.get().detailLoader={HomeworkDetailPage(html,url)}
        val saves=AtomicInteger();controller.get().imageSaveAction={urls,_,_->saves.incrementAndGet();HomeworkImageSaveResult(urls.size,urls.size,emptyList())}
        val activity=controller.setup().get();val root=activity.window.decorView
        try {
            await{views(root).filterIsInstance<TextView>().any{it.text.toString().contains("2 道题")}}
            val clipboard=app.getSystemService(ClipboardManager::class.java);clipboard.setPrimaryClip(ClipData.newPlainText("sentinel","sentinel"))
            app.prefs.edit().putString("identity","other-owner").commit()
            views(root).filterIsInstance<Button>().single{it.text=="复制全部题目文字"}.performClick()
            views(root).filterIsInstance<Button>().single{it.text=="保存全部题图到相册（2 张）"}.performClick()
            assertEquals("sentinel",clipboard.primaryClip!!.getItemAt(0).text.toString());assertEquals(0,saves.get())
        }finally{controller.pause().stop().destroy()}
        val staleReads=AtomicInteger();val stale=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        stale.get().detailLoader={staleReads.incrementAndGet();HomeworkDetailPage(html,url)}
        try{stale.setup();assertTrue(stale.get().isFinishing);assertEquals(0,staleReads.get())}
        finally{stale.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun beginningUnstartedWorkNeedsItsOwnButtonAndIsNeverAnAutomaticRetry() {
        app.prefs.edit().putString("identity","export-owner").putBoolean("loggedIn",true).commit()
        val reads=AtomicInteger()
        val controller=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        controller.get().detailLoader={if(reads.incrementAndGet()==1)throw HomeworkStartRequired();HomeworkDetailPage(html,url)}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        try {
            await{text().contains("进入这份作业并读取题目")}
            assertEquals(1,reads.get())
            assertFalse(views(root).filterIsInstance<Button>().single{it.text=="复制全部题目文字"}.isEnabled)
            views(root).filterIsInstance<Button>().single{it.text=="进入这份作业并读取题目"}.performClick()
            await{text().contains("2 道题")}
            assertEquals(2,reads.get())
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun cancellingAReadAllowsFreshReadWithoutReusingCancelledSource() {
        app.prefs.edit().putString("identity","export-owner").putBoolean("loggedIn",true).commit()
        val reads=AtomicInteger();val entered=java.util.concurrent.CountDownLatch(1)
        val controller=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        controller.get().detailLoader={if(reads.incrementAndGet()==1){entered.countDown();Thread.sleep(20_000)};HomeworkDetailPage(html,url)}
        val activity=controller.setup().get();val root=activity.window.decorView
        try {
            await{entered.count==0L}
            views(root).filterIsInstance<Button>().single{it.text=="停止读取"}.performClick()
            views(root).filterIsInstance<Button>().single{it.text=="重新读取题目"}.performClick()
            await{views(root).filterIsInstance<TextView>().any{it.text.toString().contains("2 道题")}}
            assertEquals(2,reads.get())
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun incompletePaginationCannotBeCopiedAsTheWholeAssignment() {
        app.prefs.edit().putString("identity","export-owner").putBoolean("loggedIn",true).commit()
        val controller=Robolectric.buildActivity(HomeworkExportActivity::class.java,intent())
        controller.get().detailLoader={HomeworkDetailPage(html,url,expectedQuestions=3)}
        val activity=controller.setup().get();val root=activity.window.decorView
        val clipboard=app.getSystemService(ClipboardManager::class.java)
        try {
            await{views(root).filterIsInstance<TextView>().any{it.text.toString().contains("题目尚未读全")}}
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel","sentinel"))
            val all=views(root).filterIsInstance<Button>().single{it.text=="复制全部题目文字"}
            assertFalse(all.isEnabled);all.performClick()
            assertEquals("sentinel",clipboard.primaryClip!!.getItemAt(0).text.toString())
            views(root).filterIsInstance<Button>().first{it.text=="复制本题文字"}.performClick()
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("第一题的题干"))
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
}
