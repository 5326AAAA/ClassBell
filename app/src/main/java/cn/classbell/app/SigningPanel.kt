package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SigningPanel(private val activity: Activity, private val app: BellApp, private val changed: () -> Unit,
                   private val createSource: (() -> Unit) -> SignSource = { app.signing.source(it) }) : LinearLayout(activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var task: Future<*>?=null
    private var source: SignSource?=null
    @Volatile private var generation=0
    private var identity=""
    private var session=-1
    private var selection=emptySet<String>()
    private var rendering=false
    private var busy=false
    private var presetOwner:String?=null
    private var dialog: AlertDialog?=null
    private val photos=mutableSetOf<File>()
    private val auto: CheckBox
    private val photoAuto:CheckBox
    private val presetStatus:TextView
    private val progress: TextView
    private val refresh: Button
    private val historyRefresh: Button
    private val cancel: Button
    private val records: TextView
    private val items: LinearLayout
    private val historyItems: LinearLayout
    private var found=emptyList<SignActivity>()
    private var archivedActivities=emptyList<SignActivity>()
    private var historyCourse:Course?=null
    private var historyRecords:List<CourseActivity>?=null
    private val sections=List(4) { StudyUi.column(activity) }
    private val sectionTabs=mutableMapOf<Int,Button>()
    private val sectionNames=listOf("sign","photo","records","history")
    private val accountSummary:TextView
    init {
        orientation=VERTICAL
        val accountCard=StudyUi.card(activity,this)
        val accountRow=LinearLayout(activity).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        val accountText=StudyUi.column(activity)
        accountText.addView(text("签到账号",16,true))
        accountSummary=text("",13).apply{setTextColor(ModernUi.strongPrimary)}
        accountText.addView(accountSummary)
        accountRow.addView(accountText,LayoutParams(0,-2,1f))
        accountRow.addView(button("关联账号") {
            activity.startActivity(Intent(activity,BoundAccountsActivity::class.java))
        }.apply{
            ModernUi.button(this,textOnly=true)
            setPadding(dp(8),0,dp(4),0)
        },LayoutParams(-2,-2))
        accountCard.addView(accountRow)
        val tabs=LinearLayout(activity).apply{setPadding(0,dp(4),0,dp(12))}
        listOf(0 to "进行中",3 to "已结束",1 to "设置",2 to "记录").forEachIndexed{position,(index,title)->
            val tab=button(title){showSection(index)}
            sectionTabs[index]=tab
            tabs.addView(tab,LayoutParams(0,-2,1f).apply{if(position>0)marginStart=dp(6)})
        }
        addView(tabs)
        progress=text("",13).apply{visibility=View.GONE}
        addView(progress)
        cancel=button("取消当前查询") { cancel(); progress.text="查询已取消。" }
        addView(cancel)
        sections.forEach{addView(it)}

        val current=StudyUi.card(activity,sections[0])
        current.addView(text("进行中的签到",19,true))
        current.addView(text("查询提醒课程。选中活动后，应用会核对该账号的签到要求。",13))
        refresh=button("更新进行中的签到") { load() }.apply{ModernUi.button(this,primaryAction=true)}
        current.addView(refresh)
        items=StudyUi.column(activity)
        sections[0].addView(items)
        val normalSettings=StudyUi.card(activity,sections[1])
        normalSettings.addView(text("自动提交",16,true))
        auto=CheckBox(activity).apply {
            text="普通签到自动提交"; ModernUi.toggle(this)
            setOnCheckedChangeListener { _,value -> if (!rendering) {
                if (value && (!app.prefs.getBoolean("loggedIn",false) || app.selectedCourses().isEmpty())) {
                    toast("请先登录并选择监测课程。"); render()
                } else {
                    if (value) MonitorService.stopMonitoring(activity)
                    app.prefs.edit().putBoolean("autoNormal.$identity",value).apply()
                    if (value) app.prefs.edit().putBoolean("fallbackPolling",true).putBoolean("verifyOnNotification",true).apply()
                    toast(if (value) "已开启，备用轮询已启用；请回首页重新开始监测。" else "普通签到自动提交已关闭。")
                    changed(); render()
                }
            } }
        }
        normalSettings.addView(auto)
        normalSettings.addView(text("开启后会启用备用检查，仅处理所选提醒课程。",12))

        val photo=StudyUi.card(activity,sections[1])
        photo.addView(text("预选照片",19,true))
        presetStatus=text("尚未预选照片",13);photo.addView(presetStatus)
        photoAuto=CheckBox(activity).apply {
            text="拍照签到自动提交预选照片";ModernUi.toggle(this)
            setOnCheckedChangeListener {_,value->if(!rendering) {
                if(value && (identity.isBlank() || !app.prefs.getBoolean("loggedIn",false) || app.selectedCourses().isEmpty() || app.savedSignPhotos.file(identity)==null)) {
                    toast("请先登录、选择监测课程并预选照片。");render()
                } else {
                    if(value)MonitorService.stopMonitoring(activity)
                    app.prefs.edit().putBoolean("autoPhoto.$identity",value).apply()
                    if(value)app.prefs.edit().putBoolean("fallbackPolling",true).putBoolean("verifyOnNotification",true).apply()
                    toast(if(value)"已开启备用轮询，请回首页重新开始监测。" else "拍照签到自动提交已关闭。")
                    changed();render()
                }
            }}
        }
        normalSettings.addView(photoAuto)
        normalSettings.addView(text("仅用于当前账号和所选课程。拍照自动提交需要预选照片。",12))
        val choosePhoto=button("预选 / 更换相册照片") {
            try {allowed()}catch (e: ApiProblem) { DiagnosticLog.error("SigningPanel", e);toast(e.message.orEmpty());return@button}
            if(busy){toast("请先等待当前操作完成。");return@button}
            presetOwner=identity
            try {
                SignPhoto.openPicker(activity,PRESET_REQUEST)
            } catch (diagnosticError: Exception) { DiagnosticLog.error("SigningPanel", diagnosticError);presetOwner=null;toast("无法打开系统选图器。")}
        }
        ModernUi.button(choosePhoto,primaryAction=true)
        photo.addView(choosePhoto)
        val photoActions=LinearLayout(activity)
        photoActions.addView(button("预览照片") {app.savedSignPhotos.file(identity)?.let{previewPreset(it,false)} ?: toast("尚未预选照片。")},
            LayoutParams(0,-2,1f).apply{topMargin=dp(8);marginEnd=dp(8)})
        photoActions.addView(button("清除照片") {
            if(identity.isNotBlank()){app.savedSignPhotos.clear(identity);progress.text="照片已清除，拍照自动提交已关闭。";render();toast("照片已清除，拍照自动提交已关闭。")}
        }.apply{contentDescription="清除预选照片"},LayoutParams(0,-2,1f).apply{topMargin=dp(8)})
        photo.addView(photoActions)

        val capabilities=StudyUi.card(activity,sections[1])
        capabilities.addView(text("功能状态",16,true))
        capabilities.addView(text("普通、拍照和签到码 · 可用",13).apply{setTextColor(ModernUi.strongPrimary)})
        capabilities.addView(text("扫码、手势 · 预览，按活动要求核对",13))
        capabilities.addView(text("位置签到 · 不可用",13,true).apply{setTextColor(ModernUi.danger)})
        capabilities.addView(text("需要位置的扫码、签退和额外身份验证也暂不支持在应用内处理。",12))

        val history=StudyUi.card(activity,sections[2])
        history.addView(text("最近提交结果",19,true))
        records=text("",13);history.addView(records)
        val archived=StudyUi.card(activity,sections[3])
        archived.addView(text("已结束的签到",18,true))
        archived.addView(text("选择一门课，查看已结束活动的个人结果。",13))
        historyRefresh=button("选择历史签到课程"){chooseHistoryCourse()}
            .apply{ModernUi.button(this,primaryAction=true)}
        archived.addView(historyRefresh)
        historyItems=StudyUi.column(activity)
        sections[3].addView(historyItems)
        progress.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {progress.visibility=if(s.isNullOrBlank())View.GONE else View.VISIBLE}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        showSection(sectionNames.indexOf(app.prefs.getString("ui.signingSection","sign")).coerceAtLeast(0))
        render()
    }
    private fun showSection(index:Int) {
        sections.forEachIndexed{i,view->view.visibility=if(i==index)View.VISIBLE else View.GONE}
        sectionTabs.forEach { (i, view) ->
            ModernUi.button(view,primaryAction=i==index,primaryTint=ModernUi.strongPrimary)
            view.textSize=12f
            view.minWidth=0
            view.setPadding(dp(3),dp(10),dp(3),dp(10))
            view.isSelected=i==index
            view.contentDescription="签到中心${view.text}，${if(i==index)"已选择" else "未选择"}"
        }
        app.prefs.edit().putString("ui.signingSection",sectionNames[index]).apply()
    }
    fun render() {
        val current=app.prefs.getString("identity","").orEmpty()
        val currentSession=app.signSessionGeneration
        val selected=app.selectedCourses().map{it.key}.toSet()
        val loggedIn=app.prefs.getBoolean("loggedIn",false)
        val reset=identity!=current || session!=currentSession || selection!=selected || !loggedIn
        if (reset) {
            if (busy || found.isNotEmpty() || dialog!=null) cancel()
            found=emptyList();historyCourse=null;historyRecords=null
            progress.text="";identity=current;session=currentSession;selection=selected
        }
        rendering=true
        auto.isChecked=identity.isNotBlank() && app.prefs.getBoolean("autoNormal.$identity",false)
        photoAuto.isChecked=identity.isNotBlank() && app.prefs.getBoolean("autoPhoto.$identity",false)
        presetStatus.text=if(identity.isNotBlank() && app.savedSignPhotos.file(identity)!=null) "已预选照片 · 仅用于当前账号" else "当前账号尚未预选照片"
        accountSummary.text=if(app.prefs.getBoolean("loggedIn",false))
            "${app.displayName().ifBlank{"当前账号"}} · ${selected.size} 门提醒课程"
            else "登录后可查看课程签到"
        rendering=false
        refresh.isEnabled=!busy; historyRefresh.isEnabled=!busy; cancel.visibility=if(busy) View.VISIBLE else View.GONE
        records.text=if(identity.isBlank()) "登录后显示当前账号的提交结果。" else app.signing.ledger.description(identity)
        val ignored=if(loggedIn)app.signing.ledger.ignoredActivities(identity) else emptyList()
        if(reset || archivedActivities!=ignored)showLists(ignored)
    }
    private fun allowed(requireSelected:Boolean=true) {
        if (identity.isBlank() || !app.prefs.getBoolean("loggedIn",false)) throw ApiProblem("请先登录学习通。")
        if(requireSelected && app.selectedCourses().isEmpty()) throw ApiProblem("请先选择提醒课程。")
    }
    private fun <T> work(message: String, action: (SignSource) -> T, diagnostic: String? = null,
                         requireSelected:Boolean=true, done: (T)->Unit): Boolean {
        try { allowed(requireSelected) } catch (e: ApiProblem) { DiagnosticLog.error("SigningPanel", e); toast(e.message.orEmpty());return false }
        if(busy) { toast("已有签到操作正在进行。");return false }
        val token=++generation
        val owner=identity
        val revision=app.signSessionGeneration
        fun current()=token==generation && owner==identity && owner==app.prefs.getString("identity","") &&
            revision==app.signSessionGeneration && app.prefs.getBoolean("loggedIn",false)
        source=createSource { if (generation!=token) throw ApiProblem("操作已取消。") }
        val api=source!!
        busy=true;progress.text=message;render()
        task=worker.submit {
            try {
                val value=DiagnosticLog.operation("SigningTask",message){action(api)}
                if(value is File) synchronized(photos) { if(current()) photos.add(value) else value.delete() }
                if(!current()) { if(value is File) value.delete() }
                else main.post {
                    if(current()) { busy=false;source=null;task=null;render();done(value) }
                    else if(value is File) value.delete()
                }
            } catch (e: Exception) { DiagnosticLog.error("SigningPanel", e);
            val message=when(e) { is ApiProblem,is LoginRequired -> e.message.orEmpty();else -> "查询中断，请重试。" }
                main.post { if(current()) {
                    busy=false;source=null;task=null;progress.text=message;render()
                    if (diagnostic != null) AlertTrace.add(activity, "$diagnostic · $message")
                } }
            } finally { api.cancel() }
        }
        return true
    }
    private fun load() {
        val selected=app.selectedCourses()
        work("正在查询所选课程的进行中签到…", { api ->
            val values=mutableListOf<SignActivity>()
            val failed=mutableListOf<String>()
            for (course in selected) {
                if(Thread.currentThread().isInterrupted) throw ApiProblem("操作已取消。")
                try {values+=api.activities(course).filter { it.kind==ReminderKind.SIGN_IN }}
                catch(e:LoginRequired){throw e}
                catch(e:java.util.concurrent.CancellationException){throw e}
                catch(e:Exception){DiagnosticLog.event("SignCourseScan",DiagnosticLog.course(course));DiagnosticLog.error("SignCourseScan",e);failed+=course.name}
                if(values.size>=30) break
            }
            values.distinctBy { it.key }.take(30) to failed
        }, diagnostic = "手动更新签到") { result ->
            val (values,failed)=result
            found=values
            app.signing.ledger.rememberIgnoredActivities(identity,values)
            showLists()
            val count=ongoingItems().size
            progress.text=when {
                count>0 -> "显示 $count 项进行中签到。"
                failed.isNotEmpty() -> "已读取的课程未发现进行中签到。"
                values.isNotEmpty() -> "${values.size} 项签到已停止检测，可在「已结束」查看。"
                else -> "所选课程当前没有进行中的签到。"
            }
            if(failed.isNotEmpty())progress.append("\n未能读取 ${failed.size} 门课程：${failed.joinToString("、")}。可重试并从我的页面导出日志。")
            AlertTrace.add(activity, "手动更新签到 · 显示 $count 项进行中 · 跳过 ${values.size-count} 项 · 失败 ${failed.size} 门课程")
        }
    }
    private fun ongoingItems()=found.filterNot{app.signing.ledger.ignored(identity,it.key)}

    private fun showLists(ignored:List<SignActivity> = app.signing.ledger.ignoredActivities(identity)) {
        archivedActivities=ignored
        showItems()
        showHistory()
    }

    private fun chooseHistoryCourse() {
        val courses=app.courses()
        if(!app.prefs.getBoolean("loggedIn",false) || courses.isEmpty()) {
            toast("请先登录并读取课程。")
            return
        }
        val owner=identity
        val revision=session
        val nameCounts=courses.groupingBy{it.name}.eachCount()
        val names=courses.map{course->
            if((nameCounts[course.name] ?: 0)>1) "${course.name} · ${course.className.ifBlank{course.classId}}"
            else course.name
        }
        ModernUi.dialog(activity).setTitle("查看哪门课的历史签到")
            .setItems(names.toTypedArray()){_,index->
                if(owner!=app.prefs.getString("identity","") || revision!=app.signSessionGeneration)return@setItems
                val course=courses[index]
                historyCourse=course
                historyRecords=null
                showHistory()
                work("正在读取 ${course.name} 的历史签到…", { api ->
                    api.activityFeed(course).filter{it.status==2 && it.isAttendance}.take(150)
                }, requireSelected=false) { history ->
                    progress.text=""
                    historyRecords=history
                    app.signing.ledger.rememberIgnoredActivities(identity,history.filter{it.activeType==2}.map{it.sign})
                    showLists()
                }
            }.setNegativeButton("取消",null).show()
    }

    private fun showHistory() {
        val course=historyCourse
        if(course==null) {
            historyItems.removeAllViews()
            return
        }
        val owner=identity
        val revision=session
        showCourseSignHistory(activity,historyItems,course,historyRecords,archivedActivities){item->
            setDetectionIgnored(item,false,owner,revision)
        }
    }

    private fun setDetectionIgnored(item:SignActivity,ignored:Boolean,owner:String,revision:Int) {
        if(owner.isBlank() || revision!=app.signSessionGeneration ||
            owner!=app.prefs.getString("identity","") || !app.prefs.getBoolean("loggedIn",false))return
        app.setSignDetectionIgnored(owner,item,ignored)
        showLists()
        val message=if(ignored)"已归档，可在这门课的「已结束签到」查看。" else "已恢复检测。"
        if(!busy)progress.text=message
        toast(message)
    }

    private fun showItems() {
        items.removeAllViews()
        val ongoing=ongoingItems()
        if(ongoing.isEmpty())return
        val boundCourseKeys=app.boundAccounts.list().flatMap{it.courses}.map{it.key}.toSet()
        ongoing.forEach { item ->
            val card=StudyUi.card(activity,items)
            val owner=identity
            val revision=session
            card.addView(text("进行中",12,true).apply {
                setTextColor(ModernUi.strongPrimary)
                background=ModernUi.shape(activity,ModernUi.soft,10)
                setPadding(dp(10),dp(4),dp(10),dp(4));layoutParams=LayoutParams(-2,-2)
            })
            card.addView(text(item.course.name,17,true))
            val label=if(item.mayRequirePhoto && item.title in setOf("签到","普通签到")) "普通 / 拍照签到 · 类型待核对" else item.title
            card.addView(text(label,13))
            when(item.otherId) {
                2 -> card.addView(text("扫码签到 · 预览",12,true))
                3 -> card.addView(text("手势签到 · 预览",12,true))
                4 -> card.addView(text("位置签到 · 不可用",12,true).apply{setTextColor(ModernUi.danger)})
            }
            card.addView(button("查看要求并处理签到") {
                activity.startActivity(SignActionActivity.intent(activity,item,monitoredPhoto=true))
            }.apply{ModernUi.button(this,primaryAction=true)})
            if(item.course.key in boundCourseKeys) card.addView(button("选择关联账号") {
                activity.startActivity(MultiAccountSignActivity.intent(activity,item))
            })
            card.addView(button("不检测这个签到") {
                setDetectionIgnored(item,true,owner,revision)
            }.apply{ModernUi.button(this,textOnly=true);textSize=13f})

        }
    }
    fun onPresetResult(uri:Uri?) {
        val owner=presetOwner;presetOwner=null
        if(owner==null || owner!=identity || owner!=app.prefs.getString("identity","") || uri==null)return
        work("正在缩小预选照片…",{_->SignPhoto.prepare(activity,uri)}) {file->progress.text="照片预览已就绪，请确认是否保存。";previewPreset(file,true)}
    }
    private fun previewPreset(file:File,save:Boolean) {
        val owner=identity
        var feedback="预选照片未更改。"
        val box=LinearLayout(activity).apply{orientation=VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
        box.addView(text(if(save)"保存后仅保留在本机；开启拍照自动提交后，符合要求的新签到会使用此图。" else "当前账号预选照片。自动提交由独立开关控制。",14))
        val bitmap=file.inputStream().use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=2})}
        val image=ImageView(activity).apply{adjustViewBounds=true;setImageBitmap(bitmap)}
        box.addView(image,LayoutParams(-1,dp(220)))
        val builder=ModernUi.dialog(activity).setTitle("预选照片").setView(box).setNegativeButton("关闭",null)
        if(save)builder.setPositiveButton("保存预选照片"){_,_->
            if(owner==identity && owner==app.prefs.getString("identity","") && app.prefs.getBoolean("loggedIn",false)) {
                diagnosticRunCatching{app.savedSignPhotos.replace(owner,file)}.onSuccess{
                    feedback="预选照片已保存，自动提交由开关控制。";toast("照片已保存，请按需开启自动提交。");render()
                }.onFailure{feedback="照片保存失败，请重选。";toast(feedback)}
            }
        }
        dialog=builder.create().apply{
            setOnDismissListener{image.setImageDrawable(null);bitmap?.recycle();if(save){file.delete();synchronized(photos){photos.remove(file)};progress.text=feedback};dialog=null}
            show()
        }
    }
    fun cancel() {
        generation++;source?.cancel();task?.cancel(true);source=null;task=null;busy=false;presetOwner=null
        dialog?.dismiss();dialog=null
        synchronized(photos){photos.forEach {it.delete()};photos.clear()}
        renderControls()
    }
    private fun renderControls() { refresh.isEnabled=true;historyRefresh.isEnabled=true;cancel.visibility=View.GONE }
    fun close() { cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null) }
    private fun text(value:String,size:Int,bold:Boolean=false)=TextView(activity).apply { text=value;textSize=size.toFloat();setTextColor(if(bold) ModernUi.ink else ModernUi.muted);setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(null,android.graphics.Typeface.BOLD) }
    private fun button(value:String,action:()->Unit)=Button(activity).apply { text=value;isAllCaps=false;ModernUi.button(this);layoutParams=LayoutParams(-1,-2).apply{topMargin=dp(8)};setLoggedClickListener{action()} }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(value:String)=Toast.makeText(activity,value,Toast.LENGTH_LONG).show()
    companion object { const val PRESET_REQUEST=4202 }
}
