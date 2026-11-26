package cn.classbell.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

class UpdateActivity:Activity() {
    private val updates=AppUpdates()
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var body:LinearLayout
    @Volatile private var closed=false
    private var busy=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=StudyUi.column(this).apply{setBackgroundColor(ModernUi.background)}
        root.setOnApplyWindowInsetsListener{v,i->@Suppress("DEPRECATION")
            v.setPadding(ModernUi.dp(this,18),i.systemWindowInsetTop,ModernUi.dp(this,18),i.systemWindowInsetBottom);i}
        root.addView(StudyUi.header(this,"检查更新"){finish()}.first)
        body=StudyUi.column(this);root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);render()
    }
    internal fun render(release:AppRelease?=null,message:String="手动检查 GitHub 正式发布版。") {
        body.removeAllViews()
        val card=StudyUi.card(this,body)
        card.addView(StudyUi.text(this,"ClassBell ${BuildConfig.VERSION_NAME}",23,true))
        card.addView(StudyUi.text(this,message))
        card.addView(StudyUi.button(this,if(busy)"正在检查…" else "检查最新版本",true){check()}.apply{isEnabled=!busy})
        card.addView(StudyUi.text(this,"仅访问本项目的公开发布信息，不发送学习通账号资料。下载安装包后手动覆盖安装，无需先卸载。",12))
        if(release!=null) {
            val detail=StudyUi.card(this,body)
            val current=AppVersion.parse(BuildConfig.VERSION_NAME)!!
            detail.addView(StudyUi.text(this,when{release.version>current->"发现新版本 ${release.tag}";release.version==current->"已是最新正式版";else->"当前版本高于 GitHub 最新正式版"},19,true,ModernUi.strongPrimary))
            detail.addView(StudyUi.text(this,release.notes.ifBlank{"暂无更新说明。"}))
            if(release.version>current && release.apk!=null) {
                val apk=release.apk
                detail.addView(StudyUi.text(this,"${apk.name} · ${"%.1f".format(apk.size/1048576.0)} MB",12))
                apk.digest?.let{detail.addView(StudyUi.text(this,"发布方提供的 $it",11))}
                detail.addView(StudyUi.button(this,"用浏览器下载安装包",true){open(apk.url)})
            }
            detail.addView(StudyUi.button(this,"查看 GitHub 发布页面"){open(release.page)})
        }
    }
    private fun check() {
        if(busy || closed)return
        busy=true;render(message="正在读取 GitHub 发布信息…")
        worker.submit {
            val result=runCatching{updates.latest()}
            main.post{if(!closed){busy=false;result.fold({render(it,"检查完成。")},{render(message=if(it is ApiProblem)it.message.orEmpty() else "无法连接 GitHub，请检查网络后重试。")})}}
        }
    }
    private fun open(url:String) {
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:android.content.ActivityNotFoundException){
            ModernUi.dialog(this).setTitle("打开下载地址").setMessage(url).setPositiveButton("复制链接"){_,_->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClassBell 下载",url))
            }.setNegativeButton("关闭",null).show()
        }
    }
    override fun onDestroy(){closed=true;updates.cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
}
