package cn.classbell.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class CourseActivity(val sign:SignActivity,val activeType:Int,val status:Int,val timeline:String) {
    val typeLabel get()=when(activeType) {
        74 -> "签退"
        2 -> when(sign.otherId){0->"普通 / 拍照";2->"扫码签到";3->"手势签到";4->"位置签到";5->"签到码";else->"签到"}
        else -> "课堂活动"
    }
    val isAttendance get()=activeType==2 || activeType==74
    val historyAction get()=if(activeType==74)"查看签退活动" else "查看签到结果"
    fun openHistory(activity:Activity) {
        if(activeType==74) {
            val message=listOf(sign.course.name,timeline,"签退是离场确认。此页暂不可查询签退结果。")
                .filter{it.isNotBlank()}.joinToString("\n")
            ModernUi.dialog(activity).setTitle(sign.title).setMessage(message).setPositiveButton("关闭",null).show()
        } else if(activeType==2) {
            activity.startActivity(SignActionActivity.intent(activity,sign,historyOnly=true,timeline=timeline))
        }
    }
    companion object {
        fun parse(body:String,course:Course):List<CourseActivity> {
            val root=ApiParser.json(body)
            if(root.has("result") && root.optString("result") !in setOf("1","true"))throw ApiProblem("课程活动暂时无法读取，请重试。")
            val data=root.optJSONObject("data") ?: root
            val array=data.optJSONArray("activeList") ?: throw ApiProblem("未识别到活动列表，请在课程内容中查看。")
            if(array.length()>3000)throw ApiProblem("活动记录过多，请在学习通查看。")
            return (0 until array.length()).mapNotNull {i->
                val item=array.optJSONObject(i) ?: return@mapNotNull null
                val id=sequenceOf(item.optString("id"),item.optString("activePrimaryId"))
                    .firstOrNull(SignProtocol::validId) ?: return@mapNotNull null
                val type=item.optInt("activeType",item.optInt("type",-1))
                val line=item.optString("timeLine").ifBlank{item.optString("nameFour")}.takeUnless{it=="null"}.orEmpty()
                var status=if(item.has("status"))item.optInt("status",0) else if(line.contains("进行中"))1 else if(line.contains("结束"))2 else 0
                val end=item.optString("endTime").toLongOrNull()?.let{if(it in 1_000_000_000L..9_999_999_999L)it*1000 else it} ?: 0L
                if(end>0 && end<=System.currentTimeMillis())status=2
                val name=item.optString("nameOne").ifBlank{item.optString("name").ifBlank{item.optString("title").ifBlank{"课程活动"}}}
                CourseActivity(SignActivity(id,name,course,ReminderKind.fromType(type) ?: ReminderKind.CLASSROOM,item.optString("otherId").toIntOrNull()),type,status,line)
            }.distinctBy{it.sign.key}
        }
    }
}

object CourseLinks {
    fun content(course:Course)="https://mooc1-api.chaoxing.com/mooc-ans/visit/stucoursemiddle".toHttpUrl().newBuilder()
        .addQueryParameter("courseid",course.id).addQueryParameter("clazzid",course.classId).addQueryParameter("cpi",course.cpi)
        .addQueryParameter("vc","1").addQueryParameter("ismooc2","1").addQueryParameter("v","2").build().toString()
    fun sign(sign:SignActivity)="https://mobilelearn.chaoxing.com/newsign/preSign".toHttpUrl().newBuilder()
        .addQueryParameter("activePrimaryId",sign.id).addQueryParameter("courseId",sign.course.id).addQueryParameter("classId",sign.course.classId)
        .addQueryParameter("general","1").addQueryParameter("sys","1").addQueryParameter("ls","1").addQueryParameter("appType","15").addQueryParameter("ut","s").build().toString()
}

class CourseListPanel(private val activity:Activity,private val app:BellApp,private val refresh:()->Unit,private val login:()->Unit):LinearLayout(activity) {
    private val records=StudyUi.column(activity)
    private val summary=StudyUi.text(activity,"",13)
    private val filters=mutableListOf<Button>()
    private var monitored=false
    private val search=EditText(activity).apply{hint="搜索课程 / 老师";isSingleLine=true;ModernUi.input(this)}
    init {
        orientation=VERTICAL
        val head=LinearLayout(activity).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        head.addView(summary,LayoutParams(0,-2,1f))
        head.addView(StudyUi.button(activity,"更新"){refresh()}.apply{contentDescription="更新我的课程";ModernUi.button(this,textOnly=true)},LayoutParams(-2,-2));addView(head)
        addView(search)
        val row=LinearLayout(activity)
        filters+=StudyUi.button(activity,"全部课程"){monitored=false;render()}
        filters+=StudyUi.button(activity,"仅已监测"){monitored=true;render()}
        filters.forEach{row.addView(it,LayoutParams(0,-2,1f))};addView(row);addView(records)
        (row.getChildAt(0).layoutParams as LayoutParams).marginEnd=ModernUi.dp(activity,8)
        row.setPadding(0,ModernUi.dp(activity,8),0,ModernUi.dp(activity,8))
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()};override fun afterTextChanged(s:Editable?) {}})
        render()
    }
    fun render() {
        filters.forEachIndexed{index,button->val selected=(index==1)==monitored;ModernUi.button(button,primaryAction=selected);button.isSelected=selected;button.contentDescription="${button.text}，${if(selected)"已选择" else "未选择"}"}
        if(!app.prefs.getBoolean("loggedIn",false)) {
            summary.text="登录后查看自己的课程、签到活动和作业。";records.removeAllViews();records.addView(StudyUi.button(activity,"去账号中心登录",true){login()});return
        }
        val selected=app.selectedCourses().map{it.key}.toSet();val all=app.courses();val query=search.text.toString().trim()
        summary.text="${all.size} 门课程"
        val values=all.filter{(!monitored || it.key in selected) && (it.name+it.teacher+it.className).contains(query,true)}.sortedBy{if(it.key in selected)0 else 1}
        records.removeAllViews()
        if(values.isEmpty())records.addView(StudyUi.text(activity,"没有符合条件的课程。"))
        for(course in values) {
            val card=StudyUi.card(activity,records)
            card.contentDescription="进入课程：${course.name}";card.isFocusable=true
            card.setLoggedClickListener{
                activity.startActivity(Intent(activity,CourseDetailActivity::class.java).putExtra("courseKey",course.key))
            }
            card.addView(StudyUi.text(activity,course.name,18,true))
            card.addView(StudyUi.text(activity,listOf(course.teacher,course.className).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{"班级 ${course.classId}"},12))
            card.addView(StudyUi.text(activity,if(course.key in selected)"● 已加入提醒" else "未加入提醒",12,color=ModernUi.primary))
        }
    }
}

class CourseDetailActivity:StudyActivity() {
    private companion object {
        const val RESULTS_TAB="签到结果"
    }

    internal var feedSource:((Course)->List<CourseActivity>)?=null
    private lateinit var course:Course
    private lateinit var content:LinearLayout
    private lateinit var records:LinearLayout
    private var activityFeed:List<CourseActivity>?=null
    private var showClassroomActivities=false
    private var homework:HomeworkPanel?=null
    private var tab=RESULTS_TAB
    private val tabs=mutableListOf<Button>()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        course=app.courses().firstOrNull{it.key==intent.getStringExtra("courseKey")} ?: run{finish();return}
        titleView.text="课程详情"
        val hero=StudyUi.card(this,body);hero.addView(StudyUi.text(this,course.name,23,true));hero.addView(StudyUi.text(this,listOf(course.teacher,course.className).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{"班级 ${course.classId}"}))
        val row=LinearLayout(this)
        for(name in listOf(RESULTS_TAB,"课程作业","课程内容")){val button=StudyUi.button(this,name){cancelWork();tab=name;showTab()};tabs+=button;row.addView(button,LinearLayout.LayoutParams(0,-2,1f))}
        for(i in 0 until row.childCount-1)(row.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginEnd=ModernUi.dp(this,8)
        body.addView(row);content=StudyUi.column(this);body.addView(content);showTab()
    }
    private fun showTab() {
        tabs.forEach{button->val selected=button.text==tab;ModernUi.button(button,primaryAction=selected,primaryTint=ModernUi.strongPrimary);button.isSelected=selected;button.contentDescription="${button.text}，${if(selected)"已选择" else "未选择"}"}
        homework?.close();homework=null;content.removeAllViews();state.text=""
        when(tab) {
            "课程作业" -> {homework=HomeworkPanel(this,app,{finish()},fixedCourses=setOf(course.key));content.addView(homework);homework?.setPageVisible(true)}
            "课程内容" -> {val card=StudyUi.card(this,content);card.addView(StudyUi.text(this,"章节、资料与课堂内容",19,true));card.addView(StudyUi.text(this,"在应用内打开课程页面。预览过的资料可以勾选批量下载。"));card.addView(StudyUi.button(this,"打开课程内容",true){OfficialPageActivity.open(this,CourseLinks.content(course),course.name,courseMaterials=true)})}
            RESULTS_TAB -> {
                content.addView(StudyUi.button(this,"更新签到与课堂活动"){loadSignHistory(showActivities=true)})
                records=StudyUi.column(this)
                content.addView(records)
                renderSignHistory()
                loadSignHistory()
            }
        }
    }

    private fun loadSignHistory(showActivities:Boolean=false) {
        if(working)return
        val message=if(showActivities)"正在更新签到与课堂活动…" else "正在读取签到记录…"
        work(message,{feedSource?.invoke(course) ?: api.activityFeed(course)}) {values->
            activityFeed=values
            showClassroomActivities=showClassroomActivities || showActivities
            val signIns=values.filter{it.activeType==2}.map{it.sign}
            app.signing.ledger.rememberIgnoredActivities(sessionOwner,signIns)
            renderSignHistory()
        }
    }

    private fun renderSignHistory() {
        if(!valid())return
        val owner=sessionOwner
        val ledger=app.signing.ledger
        showCourseSignHistory(this,records,course,activityFeed,ledger.ignoredActivities(owner)){item->
            if(valid()) {
                app.setSignDetectionIgnored(owner,item,false)
                renderSignHistory()
            }
        }
        if(showClassroomActivities) {
            val activities=activityFeed.orEmpty().filter { item ->
                item.status==1 && item.activeType==2 && !ledger.ignored(owner,item.sign.key) ||
                    !item.isAttendance
            }
            records.addView(StudyUi.text(this,"进行中签到与课堂活动",16,true))
            if(activities.isEmpty())records.addView(StudyUi.text(this,"这门课暂无进行中签到或课堂活动。",13))
            for(item in activities.sortedBy{it.status!=1}.take(150)) {
                val status=when(item.status){1->"进行中";2->"已结束";else->"状态待确认"}
                val card=StudyUi.card(this,records)
                card.addView(StudyUi.text(this,item.sign.title,16,true))
                card.addView(StudyUi.text(this,listOf(item.typeLabel,status,item.timeline)
                    .filter{it.isNotBlank()}.joinToString(" · "),13))
                if(item.isAttendance)card.addView(StudyUi.button(this,"查看签到"){
                    startActivity(SignActionActivity.intent(this,item.sign,timeline=item.timeline))
                })
            }
        }
    }
    override fun onStop(){homework?.setPageVisible(false);super.onStop()}
    override fun onResume(){
        super.onResume()
        homework?.setPageVisible(true)
        if(tab==RESULTS_TAB && ::records.isInitialized)renderSignHistory()
    }
    override fun onDestroy(){homework?.close();super.onDestroy()}
}
