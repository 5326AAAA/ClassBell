package cn.classbell.app

import android.app.Activity
import android.widget.LinearLayout

/** The same course history is shown from Course Detail and the Signing Center. */
internal fun showCourseSignHistory(
    activity:Activity,
    parent:LinearLayout,
    course:Course,
    feed:List<CourseActivity>?,
    archived:List<SignActivity>,
    restore:(SignActivity)->Unit
) {
    parent.removeAllViews()
    val paused=archived.filter{it.course.key==course.key}.associateBy{it.key}
    val ended=feed.orEmpty().asSequence()
        .filter{it.sign.course.key==course.key && it.isAttendance && it.status==2}
        .distinctBy{it.sign.key}.take(150).toList()
    val endedKeys=ended.filter{it.activeType==2}.map{it.sign.key}.toSet()
    val remaining=paused.values.filterNot{it.key in endedKeys}

    parent.addView(StudyUi.text(activity,"已结束签到 · ${course.name}",16,true))
    if(feed!=null && ended.isEmpty() && remaining.isEmpty()) {
        parent.addView(StudyUi.text(activity,"这门课没有已结束或已归档的签到。",13))
    }
    for(record in ended) {
        val card=StudyUi.card(activity,parent)
        card.addView(StudyUi.text(activity,record.sign.title,16,true))
        val detail=listOf(record.typeLabel,record.timeline).filter{it.isNotBlank()}.joinToString(" · ")
        card.addView(StudyUi.text(activity,detail,13))
        card.addView(StudyUi.button(activity,record.historyAction){record.openHistory(activity)})
        if(record.activeType==2)paused[record.sign.key]?.let{addPausedState(activity,card,it,restore)}
    }
    for(item in remaining) {
        val card=StudyUi.card(activity,parent)
        card.addView(StudyUi.text(activity,item.title,16,true))
        addPausedState(activity,card,item,restore)
    }
}

private fun addPausedState(activity:Activity,card:LinearLayout,item:SignActivity,restore:(SignActivity)->Unit) {
    card.addView(StudyUi.text(activity,"已停止检测",12))
    card.addView(StudyUi.button(activity,"恢复检测这个签到"){restore(item)}
        .apply{ModernUi.button(this,textOnly=true);textSize=13f})
}
