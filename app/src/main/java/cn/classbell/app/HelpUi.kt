package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.widget.*

/** A readable help sheet, shared by the entry menu and the long-form pages. */
object HelpUi {
    const val PROJECT_URL = "https://github.com/5326AAAA/ClassBell"

    fun menu(activity:Activity,guide:()->Unit,questions:()->Unit,changes:()->Unit):AlertDialog {
        val box=column(activity)
        box.addView(label(activity,"从这里开始",12,ModernUi.primary,true))
        box.addView(label(activity,"提醒怎么开、遇到问题怎么办，都在这里。",14,ModernUi.muted))
        lateinit var dialog:AlertDialog
        for((title,detail,action) in listOf(
            Triple("GitHub 开源项目",PROJECT_URL,{openProject(activity)}),
            Triple("使用指南","权限、选课、作业与签到操作",guide),
            Triple("常见问题处理方法","不响铃、登录和后台运行",questions),
            Triple("更新日志","看看这一版改了什么",changes))) {
            val project=title=="GitHub 开源项目"
            val card=column(activity).apply {
                background=ModernUi.shape(activity,if(project)ModernUi.strongPrimary else ModernUi.soft,18)
                contentDescription=title
                setLoggedClickListener {dialog.dismiss();action()}
                isFocusable=true
            }
            card.addView(label(activity,title,17,if(project)Color.WHITE else ModernUi.ink,true))
            card.addView(label(activity,detail,12,if(project)0xFFCEE6DA.toInt() else ModernUi.muted))
            if(project) {
                card.addView(label(activity,"觉得好用，就去 GitHub 点个 Star（星标）吧，这对我很重要。感谢支持！",12,Color.WHITE,true))
                card.addView(label(activity,"点这张卡片打开仓库 → 登录 GitHub → 找到「Star ☆」并点击，让星星亮起来。",12,0xFFCEE6DA.toInt()))
            }
            box.addView(card,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ModernUi.dp(activity,10)})
        }
        val scroll=ScrollView(activity).apply{addView(box);isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false}
        dialog=ModernUi.dialog(activity).setTitle("使用帮助").setView(scroll).setNegativeButton("关闭",null).create()
        dialog.show();return dialog
    }
    private fun openProject(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
        } catch (_: ActivityNotFoundException) {
            ModernUi.dialog(activity).setTitle("GitHub 开源项目").setMessage(PROJECT_URL)
                .setPositiveButton("复制链接") { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("ClassBell GitHub", PROJECT_URL))
                    Toast.makeText(activity,"链接已复制",Toast.LENGTH_SHORT).show()
                }.setNegativeButton("关闭", null).show()
        }
    }
    fun content(activity:Activity,title:String,value:String,backgroundGuide:(()->Unit)?=null):AlertDialog {
        val box=column(activity)
        value.split("\n\n").filter{it.isNotBlank()}.forEach {section ->
            val card=column(activity).apply{background=ModernUi.shape(activity,ModernUi.background,16)}
            val lines=section.split('\n',limit=2)
            card.addView(label(activity,lines[0],15,ModernUi.primary,true))
            if(lines.size>1)card.addView(label(activity,lines[1],14,ModernUi.ink))
            box.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ModernUi.dp(activity,10)})
        }
        val scroll=ScrollView(activity).apply{addView(box);isFillViewport=false;isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false}
        val viewport=FrameLayout(activity).apply{addView(scroll,FrameLayout.LayoutParams(-1,(activity.resources.displayMetrics.heightPixels*0.56f).toInt()))}
        val builder=ModernUi.dialog(activity).setTitle(title).setView(viewport).setPositiveButton("关闭",null)
        backgroundGuide?.let{builder.setNeutralButton("后台设置步骤"){_,_->it()}}
        return builder.show()
    }
    private fun column(a:Activity)=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(ModernUi.dp(a,16),ModernUi.dp(a,10),ModernUi.dp(a,16),ModernUi.dp(a,10))}
    private fun label(a:Activity,value:String,size:Int,color:Int,bold:Boolean=false)=TextView(a).apply{
        text=value;textSize=size.toFloat();setTextColor(color);setPadding(0,ModernUi.dp(a,4),0,ModernUi.dp(a,4));setLineSpacing(ModernUi.dp(a,3).toFloat(),1f)
        if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
}
