package cn.classbell.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*

class CoursePickerActivity:StudyActivity() {
    private val chosen=linkedSetOf<String>()
    private var owner=""
    private var homework=false
    private var options=emptyList<Course>()
    private lateinit var rows:LinearLayout
    private lateinit var query:EditText
    private lateinit var count:TextView
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        owner=app.prefs.getString("identity","").orEmpty();homework=intent.getStringExtra("mode")=="homework"
        if(owner.isBlank() || !valid()){finish();return}
        titleView.text=if(homework)"作业显示课程" else "选择提醒课程"
        options=app.courses()
        if(homework) {
            val cached=EncryptedHomeworkCache(app.vault).read(owner)?.items.orEmpty()
            options=(options+cached.map{Course(it.courseKey.substringBefore(':'),it.courseKey.substringAfter(':'),it.courseName)}).distinctBy{it.key}
        }
        val previous=if(homework)HomeworkCourseFilter(app.prefs).selection(owner) ?: options.map{it.key}.toSet() else app.selectedCourses().map{it.key}.toSet()
        chosen.addAll(savedInstanceState?.getStringArrayList("chosen") ?: previous)
        val summary=StudyUi.card(this,body).apply{background=ModernUi.shape(this@CoursePickerActivity,ModernUi.soft,20)}
        summary.addView(StudyUi.text(this,if(homework)"我的作业范围" else "关注你的课堂",18,true))
        count=StudyUi.text(this,"",13);summary.addView(count)
        query=EditText(this).apply{hint="搜索课程 / 老师";isSingleLine=true;ModernUi.input(this)};body.addView(query)
        val controls=LinearLayout(this).apply{setPadding(0,ModernUi.dp(this@CoursePickerActivity,8),0,ModernUi.dp(this@CoursePickerActivity,12))}
        controls.addView(StudyUi.button(this,"全选"){chosen.addAll(options.map{it.key});render()},LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(StudyUi.button(this,"清空"){chosen.clear();render()},LinearLayout.LayoutParams(0,-2,1f))
        if(!homework)controls.addView(StudyUi.button(this,"按标签"){chooseTags()},LinearLayout.LayoutParams(0,-2,1f))
        for(i in 0 until controls.childCount-1)(controls.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginEnd=ModernUi.dp(this,8)
        body.addView(controls)
        rows=StudyUi.column(this);body.addView(rows)
        val footer=StudyUi.column(this).apply{setPadding(0,ModernUi.dp(this@CoursePickerActivity,8),0,ModernUi.dp(this@CoursePickerActivity,8));background=ModernUi.shape(this@CoursePickerActivity,ModernUi.background,16)}
        if(homework)footer.addView(StudyUi.button(this,"显示全部课程（含以后新增）"){if(valid()){HomeworkCourseFilter(app.prefs).save(owner,null);setResult(RESULT_OK);finish()}})
        else footer.addView(StudyUi.button(this,"保存并打标签"){
            if(chosen.isEmpty()){state.text="请先选至少一门课程。";return@button}
            val name=EditText(this).apply{hint="标签名称，如：本学期";isSingleLine=true;filters=arrayOf(android.text.InputFilter.LengthFilter(32));ModernUi.input(this)}
            ModernUi.dialog(this).setTitle("为所选课程保存标签").setView(name).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->
                val label=name.text.toString().trim()
                if(label.isBlank()){state.text="标签名称不能为空。";return@setPositiveButton}
                if(valid())diagnosticRunCatching{app.courseMemory.putTag(owner,label,chosen.toSet());save()}.onFailure{state.text=it.message ?: "标签保存未完成。"}
            }.show()
        }.apply{ModernUi.button(this,textOnly=true)})
        footer.addView(StudyUi.button(this,"保存选择",true){save()})
        ((body.parent as ScrollView).parent as LinearLayout).addView(footer)
        query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()};override fun afterTextChanged(s:Editable?) {}})
        render()
    }
    private fun render() {
        count.text="已选 ${chosen.size} 门 · 共 ${options.size} 门课程 · 已选优先"
        rows.removeAllViews();val value=query.text.toString().trim()
        for(course in options.filter{(it.name+it.teacher).contains(value,true)}.sortedBy{if(it.key in chosen)0 else 1}) {
            val card=StudyUi.card(this,rows)
            card.tag=course.key
            card.background=ModernUi.shape(this,if(course.key in chosen)ModernUi.soft else android.graphics.Color.WHITE,18)
            val check=CheckBox(this).apply{text=course.name;ModernUi.selection(this);isChecked=course.key in chosen;minHeight=ModernUi.dp(this@CoursePickerActivity,48)
                setOnCheckedChangeListener{_,yes->
                    if(yes)chosen.add(course.key) else chosen.remove(course.key)
                    card.background=ModernUi.shape(this@CoursePickerActivity,if(yes)ModernUi.soft else android.graphics.Color.WHITE,18)
                    count.text="已选 ${chosen.size} 门 · 共 ${options.size} 门课程 · 已选优先"
                    val scroll=body.parent as ScrollView;val position=scroll.scrollY
                    val ordered=(0 until rows.childCount).map{rows.getChildAt(it)}.sortedBy{if((it.tag as? String) in chosen)0 else 1}
                    ordered.forEachIndexed{index,view->if(rows.getChildAt(index)!==view){rows.removeView(view);rows.addView(view,index)}}
                    scroll.post{if(valid())scroll.scrollTo(0,position)}
                }}
            card.addView(check)
            card.setLoggedClickListener{check.performClick()}
            val detail=listOf(course.teacher,course.className).filter{it.isNotBlank()}.joinToString(" · ")
            if(detail.isNotBlank())card.addView(StudyUi.text(this,detail,12))
        }
    }
    private fun chooseTags() {
        val tags=app.courseMemory.tags(owner)
        if(tags.isEmpty()){state.text="还没有标签。勾选课程后点“保存并打标签”即可新建。";return}
        ChoiceSheet.show(this,"按标签选课","可多选，点击保存后才会更改提醒范围。",tags.map{it.name to "${it.courseKeys.count{key->options.any{course->course.key==key}}} 门课程"},
            primary="使用这些标签",secondary="加入当前选择") {indices,merge->
            if(indices.isEmpty()){Toast.makeText(this,"请先选一个标签",Toast.LENGTH_SHORT).show();false}
            else {val keys=app.courseMemory.coursesForTags(owner,indices.map{tags[it].name}.toSet(),options);if(!merge)chosen.clear();chosen.addAll(keys);render();true}
        }
    }
    private fun save() {
        if(!valid()){finish();return}
        if(homework)HomeworkCourseFilter(app.prefs).save(owner,chosen.toSet()) else {MonitorService.stopMonitoring(this);app.saveSelectedCourses(chosen.toSet())}
        Toast.makeText(this,if(homework)"作业显示课程已保存" else "提醒课程已保存，请重新开始监测",Toast.LENGTH_LONG).show()
        setResult(RESULT_OK);finish()
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putStringArrayList("chosen",ArrayList(chosen));super.onSaveInstanceState(outState)}
}
