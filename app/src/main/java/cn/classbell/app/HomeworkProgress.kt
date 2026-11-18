package cn.classbell.app

import android.content.SharedPreferences
import org.json.JSONObject

enum class HomeworkMark(val label:String) { COMPLETE("已完成"), INCOMPLETE("未完成") }

/** Local task planning is independent of the server snapshot and account switching. */
class HomeworkProgress(private val prefs:SharedPreferences) {
    private fun field(owner:String)="homework.progress.$owner"
    fun marks(owner:String):Map<String,HomeworkMark> {
        if(owner.isBlank())return emptyMap()
        return diagnosticRunCatching {
            val root=JSONObject(prefs.getString(field(owner),"{}").orEmpty())
            root.keys().asSequence().mapNotNull { key ->
                val mark=HomeworkMark.entries.firstOrNull{it.name==root.optJSONObject(key)?.optString("mark")}
                mark?.let{key to it}
            }.toMap()
        }.getOrDefault(emptyMap())
    }
    @Synchronized fun set(owner:String,item:HomeworkItem,mark:HomeworkMark?) {
        require(owner.isNotBlank())
        val root=JSONObject(prefs.getString(field(owner),"{}").orEmpty())
        val key=accountKey(item.key)
        if(mark==null)root.remove(key) else {
            if(!root.has(key) && root.length()>=3000)throw ApiProblem("手动标记已达 3000 项，请先恢复部分旧标记。")
            root.put(key,JSONObject().put("mark",mark.name).put("at",System.currentTimeMillis()))
        }
        check(prefs.edit().putString(field(owner),root.toString()).commit())
        DiagnosticLog.event("HomeworkProgress", "account=${DiagnosticLog.reference(owner)} ${DiagnosticLog.homework(item)} manual=${mark?.name ?: "FOLLOW_SERVER"}")
    }
    companion object {
        fun state(item:HomeworkItem,now:Long,marks:Map<String,HomeworkMark>)=when(marks[accountKey(item.key)]) {
            HomeworkMark.COMPLETE->HomeworkState.SUBMITTED
            HomeworkMark.INCOMPLETE->HomeworkState.PENDING
            null->item.state(now)
        }
    }
}
