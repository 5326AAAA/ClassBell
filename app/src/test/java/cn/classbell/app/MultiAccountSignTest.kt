package cn.classbell.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import okhttp3.Cookie
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MultiAccountSignTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val course=Course("101","201","合成课程")
    private val item=SignActivity("301","合成普通签到",course,otherId=0)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    private fun await(condition:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10)};fail("Batch did not finish")}
    private fun scenario(unbindFirst:Boolean) {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=(1..2).map{i->accounts.bind("合成账号 $i",listOf(Cookie.Builder().name("_uid").value("90$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val sends=ConcurrentLinkedQueue<String>()
        val queries=ConcurrentLinkedQueue<String>()
        val confirmedReply=AtomicBoolean(true)
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId",item.id).putExtra("title",item.title).putExtra("otherId",0)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page)
        controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity):SignPlan{guard();return SignPlan(SignMode.NORMAL,"普通签到")}
            override fun attendance(activity:SignActivity):SignOutcome {
                guard();queries+=account.id
                return if(account.id==bound.last().id && confirmedReply.get())SignOutcome(true,"已确认签到")
                else SignOutcome(false,"尚未签到",SignResultCategory.NOT_SUBMITTED)
            }
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit();sends+=account.id
                if(account.id==bound.first().id)throw IOException("synthetic lost reply")
                return SignOutcome(true,"签到成功")
            }
            override fun cancel(){}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            if(unbindFirst)accounts.unbind(bound.first().id)
            button("确认所选账号并提交").performClick()
            val confirmation=ShadowAlertDialog.getLatestAlertDialog()
            val panel=views(confirmation.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
            assertTrue(panel.contains(course.name));assertTrue(panel.contains(item.title))
            assertTrue(panel.contains(if(unbindFirst)"将提交 · 1" else "将提交 · 2"))
            if(unbindFirst){assertTrue(panel.contains("将跳过 · 1"));assertTrue(panel.contains("账号已更新或解绑"))}
            assertTrue(sends.isEmpty())
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{text().contains("处理结束") && text().contains("成功 · 签到成功")}
            if(unbindFirst){assertEquals(setOf(bound.last().id),sends.toSet());assertEquals(1,sends.size);assertTrue(text().contains("解绑"))}
            else {
                assertEquals(bound.map{it.id}.toSet(),sends.toSet());assertEquals(bound.size,sends.size);assertTrue(text().contains("结果待核实"))
                root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844)
                val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(ModernUi.background);root.draw(canvas)
                val cwd=File(System.getProperty("user.dir"));val project=if(File(cwd,"app/src").exists())cwd else cwd.parentFile
                val out=File(project,"outputs/ui-v${BuildConfig.VERSION_NAME}").apply{mkdirs()}
                File(out,"多账号签到-逐个结果.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                assertFalse(button("确认所选账号并提交").isEnabled)
                button("刷新所选账号的签到结果").performClick()
                await{text().contains("处理结束") && text().contains("已确认签到")}
                assertEquals(bound.map{it.id}.toSet(),queries.toSet());assertEquals(bound.size,queries.size)
                assertTrue(text().contains("尚未签到"))
                assertTrue(text().contains("结果待核实"))
                confirmedReply.set(false)
                button("刷新所选账号的签到结果").performClick()
                await{text().contains("处理结束") && queries.size==4}
                assertEquals(bound.associate{it.id to 2},queries.groupingBy{it}.eachCount())
                assertTrue(text().contains("成功 · 已确认签到"))
                assertFalse(button("确认所选账号并提交").isEnabled)
                assertEquals(2,sends.size)
            }
        }finally{controller.pause().stop().destroy();app.prefs.edit().clear().commit()}
    }
    @Test fun oneLostReplyDoesNotBlockOtherAccountsAndNeitherAccountIsSentTwice()=scenario(false)
    @Test fun accountUnboundAfterPreflightIsSkippedAndOtherAccountContinues()=scenario(true)
    @Test fun photosSubmitConcurrentlyWithSeparateFilesAndOnlyUnconfirmedAccountCanRetry() {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=(1..4).map{i->accounts.bind("合成照片账号 $i",listOf(Cookie.Builder().name("_uid").value("90$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val entered=CountDownLatch(4)
        val release=CountDownLatch(1)
        val sends=ConcurrentLinkedQueue<Pair<String,String>>()
        val sentFiles=ConcurrentLinkedQueue<Pair<String,String>>()
        val attempts=ConcurrentHashMap<String,Int>()
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId",item.id).putExtra("title",item.title).putExtra("otherId",0)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page)
        controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.PHOTO,"合成拍照签到")
            override fun attendance(activity:SignActivity)=SignOutcome(false,"合成查询：尚未签到",SignResultCategory.NOT_SUBMITTED)
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit()
                val n=attempts.merge(account.id,1){a,b->a+b}!!
                sends+=account.id to photo!!.readText()
                sentFiles+=account.id to photo.absolutePath
                if(n==1) {
                    entered.countDown()
                    assertTrue("All four photos must run concurrently",entered.await(10,TimeUnit.SECONDS))
                    assertTrue("Test must release the four uploads",release.await(10,TimeUnit.SECONDS))
                }
                if(account.id==bound.first().id && n==1)throw IOException("synthetic lost photo reply")
                return SignOutcome(true,"照片签到成功",photoState=PhotoTransferState.CONFIRMED)
            }
            override fun cancel(){}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        fun accountText(account:BoundAccount)=views(views(root).filterIsInstance<CheckBox>().single{it.text==account.label}.parent as View).filterIsInstance<TextView>().joinToString("\n"){it.text}
        @Suppress("UNCHECKED_CAST")
        val photos=MultiAccountSignActivity::class.java.getDeclaredField("photos").apply{isAccessible=true}.get(activity) as MutableMap<String,File>
        val created=mutableListOf<File>()
        fun supply(index:Int,content:String) {val file=File.createTempFile("synthetic-batch-photo-",".jpg",app.cacheDir).apply{writeText(content)};photos[bound[index].id]=file;created+=file}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            bound.indices.forEach{supply(it,"account-${it+1}-photo")}
            button("刷新所选账号的签到结果").performClick();await{text().contains("处理结束")}
            assertEquals("Querying before upload must preserve selected photos",4,photos.size)
            assertTrue(created.all{it.exists()})
            button("确认所选账号并提交").performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.contains("仅上传此账号对应的照片")})
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{entered.count==0L}
            assertEquals(4,sends.size)
            release.countDown()
            await{sends.size==4 && text().contains("处理结束")}
            assertEquals(bound.indices.associate{bound[it].id to "account-${it+1}-photo"},sends.toMap())
            assertEquals(bound.indices.associate{bound[it].id to created[it].absolutePath},sentFiles.toMap())
            assertEquals(4,sentFiles.map{it.second}.toSet().size)
            assertTrue(accountText(bound.first()).contains("结果待核实"))
            bound.drop(1).forEach{assertTrue(accountText(it).contains("成功 · 照片签到成功"))}
            assertTrue(photos.isEmpty());assertTrue(created.none{it.exists()})
            assertFalse(button("确认所选账号并提交").isEnabled)
            button("刷新所选账号的签到结果").performClick();await{text().contains("处理结束") && button("确认所选账号并提交").isEnabled}
            assertTrue(accountText(bound.first()).contains("合成查询：尚未签到"))
            bound.drop(1).forEach{assertTrue(accountText(it).contains("成功 · 照片签到成功"))}
            supply(0,"account-a-new-photo")
            button("确认所选账号并提交").performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{sends.size==5 && text().contains("处理结束")}
            assertEquals(bound.indices.associate{bound[it].id to if(it==0)2 else 1},attempts.toMap())
            assertEquals(bound.first().id,sends.last().first)
            assertEquals("account-a-new-photo",sends.last().second)
            assertEquals(created.last().absolutePath,sentFiles.last().second)
            bound.forEach{assertTrue(accountText(it).contains("成功 · 照片签到成功"))}
            assertTrue(photos.isEmpty());assertTrue(created.none{it.exists()})
        } finally {
            while(entered.count>0)entered.countDown()
            release.countDown();controller.pause().stop().destroy();created.forEach{it.delete()};app.prefs.edit().clear().commit()
        }
    }
    @Test fun fifthPhotoWaitsForOneOfFourUploadsAndAllAccountsKeepTheirOwnResultAndFile() {
        app.prefs.edit().clear().commit()
        var json:String?=null;val accounts=BoundAccounts({json},{json=it})
        val bound=(1..5).map{i->accounts.bind("合成边界照片账号 $i",listOf(Cookie.Builder().name("_uid").value("91$i").domain("chaoxing.com").path("/").secure().build()),listOf(course))}
        val firstFourEntered=CountDownLatch(4)
        val fifthEntered=CountDownLatch(1)
        val releases=bound.associate{it.id to CountDownLatch(1)}
        val active=AtomicInteger()
        val maximumActive=AtomicInteger()
        val sends=ConcurrentLinkedQueue<Triple<String,String,String>>()
        val page=Intent(app,MultiAccountSignActivity::class.java).putExtra("courseKey",course.key).putExtra("activeId",item.id).putExtra("title",item.title).putExtra("otherId",0)
        val controller=Robolectric.buildActivity(MultiAccountSignActivity::class.java,page)
        controller.get().accountRepository=accounts
        controller.get().sourceFactory={account,guard->object:SignSource {
            override fun activities(course:Course)=listOf(item)
            override fun plan(activity:SignActivity)=SignPlan(SignMode.PHOTO,"合成拍照签到")
            override fun submit(activity:SignActivity,photo:File?,beforeCommit:()->Unit):SignOutcome {
                guard();beforeCommit()
                val current=active.incrementAndGet()
                maximumActive.accumulateAndGet(current){a,b->maxOf(a,b)}
                try {
                    sends+=Triple(account.id,photo!!.absolutePath,photo.readText())
                    if(account.id==bound.last().id)fifthEntered.countDown() else firstFourEntered.countDown()
                    assertTrue("Test must release each upload",releases.getValue(account.id).await(10,TimeUnit.SECONDS))
                    guard()
                    return SignOutcome(true,"照片签到成功 · ${account.label}",photoState=PhotoTransferState.CONFIRMED)
                } finally {active.decrementAndGet()}
            }
            override fun cancel(){}
        }}
        val activity=controller.setup().get();val root=activity.window.decorView
        fun text()=views(root).filterIsInstance<TextView>().joinToString("\n"){it.text}
        fun button(title:String)=views(root).filterIsInstance<Button>().single{it.text==title}
        fun accountText(account:BoundAccount)=views(views(root).filterIsInstance<CheckBox>().single{it.text==account.label}.parent as View).filterIsInstance<TextView>().joinToString("\n"){it.text}
        @Suppress("UNCHECKED_CAST")
        val photos=MultiAccountSignActivity::class.java.getDeclaredField("photos").apply{isAccessible=true}.get(activity) as MutableMap<String,File>
        val created=bound.associate{account->account.id to File.createTempFile("synthetic-queued-photo-",".jpg",app.cacheDir).apply{writeText("photo-for-${account.id}")}}
        try {
            views(root).filterIsInstance<CheckBox>().forEach{it.performClick()}
            button("核对所选账号的要求").performClick();await{text().contains("处理结束")}
            photos.putAll(created)
            button("确认所选账号并提交").performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{firstFourEntered.count==0L}
            // Keep pumping the UI while the first wave is held, so an extra worker has time to expose itself.
            val queueObservationEnd=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300)
            await {
                assertEquals("The fifth photo must remain queued while four uploads are active",1L,fifthEntered.count)
                assertEquals(4,active.get());assertEquals(4,sends.size)
                System.nanoTime()>=queueObservationEnd
            }
            assertEquals(bound.take(4).map{it.id}.toSet(),sends.map{it.first}.toSet())
            assertTrue(created.values.all{it.exists()})
            releases.getValue(bound.first().id).countDown()
            await{fifthEntered.count==0L && accountText(bound.first()).contains("成功 · 照片签到成功")}
            assertEquals(5,sends.size);assertEquals(4,active.get());assertEquals(4,maximumActive.get())
            assertFalse(created.getValue(bound.first().id).exists())
            bound.drop(1).forEach {
                assertTrue(created.getValue(it.id).exists())
                assertFalse(accountText(it).contains("成功 · 照片签到成功"))
            }
            releases.values.forEach{it.countDown()}
            await{text().contains("处理结束")}
            assertEquals(0,active.get());assertEquals(4,maximumActive.get())
            assertEquals(bound.associate{it.id to 1},sends.groupingBy{it.first}.eachCount())
            assertEquals(created.mapValues{it.value.absolutePath},sends.associate{it.first to it.second})
            assertEquals(bound.associate{it.id to "photo-for-${it.id}"},sends.associate{it.first to it.third})
            assertEquals(5,sends.map{it.second}.toSet().size)
            bound.forEach{assertTrue(accountText(it).contains("成功 · 照片签到成功 · ${it.label}"))}
            assertTrue(photos.isEmpty());assertTrue(created.values.none{it.exists()})
            assertFalse(button("确认所选账号并提交").isEnabled)
        } finally {
            releases.values.forEach{it.countDown()}
            controller.pause().stop().destroy();created.values.forEach{it.delete()};app.prefs.edit().clear().commit()
        }
    }
}
