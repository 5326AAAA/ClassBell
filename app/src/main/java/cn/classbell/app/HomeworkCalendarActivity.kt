package cn.classbell.app

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import java.io.File

class HomeworkCalendarActivity:StudyActivity() {
    private var pending:File?=null
    private var alarm=true
    private var items=emptyList<HomeworkItem>()
    internal var snapshotOverride:HomeworkSnapshot?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text="作业截止日历"
        cacheDir.listFiles().orEmpty().filter{it.name.startsWith("homework-calendar-") && it.extension=="ics" &&
            System.currentTimeMillis()-it.lastModified()>86_400_000L}.forEach{it.delete()}
        alarm=savedInstanceState?.getBoolean("alarm",true) ?: true
        val name=savedInstanceState?.getString("pending")
        if(name!=null && savedInstanceState.getString("owner")==sessionOwner && name.matches(Regex("homework-calendar-[A-Za-z0-9-]+\\.ics"))) {
            pending=File(cacheDir,name).takeIf{it.isFile && it.canonicalFile.parentFile==cacheDir.canonicalFile}
        }
        render()
    }
    internal fun render() {
        body.removeAllViews()
        val snapshot=snapshotOverride ?: EncryptedHomeworkCache(app.vault).read(sessionOwner)
        val courses=intent.getStringArrayListExtra("courses")?.toSet()
        items=HomeworkCalendar.pending(snapshot?.items.orEmpty(),System.currentTimeMillis(),HomeworkProgress(app.prefs).marks(sessionOwner),courses)
        val card=StudyUi.card(this,body)
        card.addView(StudyUi.text(this,"${items.size} 项待交作业",23,true))
        card.addView(StudyUi.text(this,"导出截止时间到日历，在支持 iCalendar 的应用中导入 .ics 文件。",14))
        card.addView(StudyUi.text(this,snapshot?.let{"数据更新：${HomeworkDeadline.format(it.fetchedAt)}"} ?: "还没有作业数据，请先返回作业页更新。",12))
        card.addView(CheckBox(this).apply{text="截止前 30 分钟提醒";ModernUi.selection(this);isChecked=alarm;setOnCheckedChangeListener{_,checked->alarm=checked}})
        card.addView(StudyUi.text(this,"只导出有截止时间且未完成的作业。无需日历或全部文件权限；提醒由导入后的日历应用处理。导出后若作业变化，请重新导出并核对旧日程。",12))
        card.addView(StudyUi.button(this,"保存日历文件 (.ics)",true){export()}.apply{isEnabled=valid() && items.isNotEmpty()})
        items.take(100).forEach { item->
            val row=StudyUi.card(this,body)
            row.addView(StudyUi.text(this,item.title,16,true))
            row.addView(StudyUi.text(this,item.courseName,12))
            row.addView(StudyUi.text(this,item.deadlineText().orEmpty(),13,true,ModernUi.strongPrimary))
        }
        if(items.size>100)body.addView(StudyUi.text(this,"预览前 100 项；保存时包含全部 ${items.size} 项。",12))
    }
    @Suppress("DEPRECATION")
    private fun export() {
        if(!valid() || working || items.isEmpty())return
        val selected=items.toList();val owner=sessionOwner;val includeAlarm=alarm
        work("正在生成日历…",{
            val bytes=HomeworkCalendar.generate(owner,selected,System.currentTimeMillis(),includeAlarm)
            guard()
            File.createTempFile("homework-calendar-",".ics",cacheDir).apply{writeBytes(bytes)}
        }) { file->
            pending?.delete();pending=file
            try{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/calendar").putExtra(Intent.EXTRA_TITLE,"ClassBell-作业截止日历.ics"),SAVE)}
            catch(_:android.content.ActivityNotFoundException){pending?.delete();pending=null;state.text="未找到系统文件选择器。"}
        }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=SAVE)return
        val file=pending ?: return
        pending=null
        if(resultCode!=RESULT_OK || data?.data==null || !valid()){file.delete();state.text="已取消保存。";return}
        val uri=data.data!!
        if(uri.scheme!="content"){file.delete();state.text="不支持的文件保存位置。";return}
        work("正在保存日历…",{
            try {
                guard()
                contentResolver.openOutputStream(uri,"wt")?.use{out->
                    file.inputStream().use{input->val buffer=ByteArray(8192);while(true){guard();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}}
                } ?: throw ApiProblem("无法写入所选文件。")
            }finally{file.delete()}
        }) {state.text="日历已保存，用支持 .ics 的日历应用导入即可。"}
    }
    override fun onSaveInstanceState(out:Bundle){out.putBoolean("alarm",alarm);out.putString("pending",pending?.name);out.putString("owner",sessionOwner);super.onSaveInstanceState(out)}
    override fun onDestroy(){if(!isChangingConfigurations)pending?.delete();super.onDestroy()}
    companion object {private const val SAVE=6814}
}
