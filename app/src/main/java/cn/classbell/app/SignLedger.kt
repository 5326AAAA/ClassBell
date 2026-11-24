package cn.classbell.app

import android.content.SharedPreferences
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class SignLedger(private val prefs: SharedPreferences) {
    private class SubmissionState { val inFlight=mutableSetOf<String>() }
    private companion object {
        // Android returns one SharedPreferences object per file. Ledger wrappers must share
        // both the reservation monitor and in-flight state for that file.
        val submissionStates=java.util.WeakHashMap<SharedPreferences,SubmissionState>()
        fun stateFor(prefs:SharedPreferences)=synchronized(submissionStates) {
            submissionStates.getOrPut(prefs){SubmissionState()}
        }
    }
    private val submissionState=stateFor(prefs)
    private fun flightKey(identity:String,activity:SignActivity)="$identity:${accountKey(activity.key)}"
    fun reserveSubmission(identity:String,activity:SignActivity):Boolean = synchronized(submissionState) {
        val key=flightKey(identity,activity)
        if(key in submissionState.inFlight || accountKey(activity.key) in prefs.getStringSet("sign.confirmed.$identity",emptySet()).orEmpty() ||
            !reserve(identity,activity))return@synchronized false
        submissionState.inFlight+=key
        true
    }
    fun finishSubmission(identity:String,activity:SignActivity) = synchronized(submissionState) {
        submissionState.inFlight.remove(flightKey(identity,activity))
        Unit
    }
    fun confirmSuccess(identity:String,activity:SignActivity):Unit = synchronized(submissionState) {
        reserve(identity,activity)
        val field="sign.confirmed.$identity"
        val key=accountKey(activity.key)
        val previous=prefs.getStringSet(field,emptySet()).orEmpty()
        if(key !in previous && !prefs.edit().putStringSet(field,previous+key).commit())
            throw ApiProblem("未能保存签到成功状态，请先刷新结果。")
    }
    /** Publish the final result before another screen can reconcile a stale attendance query. */
    fun completeSubmission(identity:String,activity:SignActivity,result:SignOutcome) = synchronized(submissionState) {
        try {
            if(result.successful)confirmSuccess(identity,activity)
            else if(result.retryAllowed && result.category==SignResultCategory.FAILED)releaseRejected(identity,activity)
        } finally { submissionState.inFlight.remove(flightKey(identity,activity)) }
    }
    /** An authenticated result for this activity/account can recover a previous uncertain QR attempt. */
    fun recoverUnsubmittedQr(identity:String,activity:SignActivity,result:SignOutcome):Boolean = synchronized(submissionState) {
        if(result.category!=SignResultCategory.NOT_SUBMITTED || result.successful || activity.otherId!=2 ||
            flightKey(identity,activity) in submissionState.inFlight || accountKey(activity.key) in prefs.getStringSet("sign.confirmed.$identity",emptySet()).orEmpty())return@synchronized false
        if(attempted(identity,activity))releaseRejected(identity,activity)
        true
    }
    private fun ignoreField(identity:String)="sign.ignored.$identity"
    private fun ignoredDetailsField(identity:String)="sign.ignoredDetails.$identity"

    private fun ignoredDetails(identity:String)=diagnosticRunCatching {
        JSONObject(prefs.getString(ignoredDetailsField(identity),"{}").orEmpty())
    }.getOrDefault(JSONObject())

    private fun details(activity:SignActivity)=JSONObject()
        .put("id",activity.id)
        .put("title",activity.title)
        .put("course",activity.course.copy(teacher="",className="",cpi="").json())
        .put("otherId",activity.otherId ?: JSONObject.NULL)

    fun ignored(identity:String,activityKey:String):Boolean =
        identity.isNotBlank() && accountKey(activityKey) in prefs.getStringSet(ignoreField(identity),emptySet()).orEmpty()

    fun ignoredActivities(identity:String):List<SignActivity> {
        if(identity.isBlank())return emptyList()
        val keys=prefs.getStringSet(ignoreField(identity),emptySet()).orEmpty()
        val records=ignoredDetails(identity)
        return records.keys().asSequence().filter{it in keys}.mapNotNull { key ->
            val row=records.optJSONObject(key) ?: return@mapNotNull null
            val course=row.optJSONObject("course") ?: return@mapNotNull null
            val item=SignActivity(row.optString("id"),row.optString("title"),
                Course(course.optString("id"),course.optString("classId"),course.optString("name")),
                otherId=row.optString("otherId").toIntOrNull())
            item.takeIf{SignProtocol.validId(it.id) && accountKey(it.key)==key}
        }.toList().asReversed()
    }

    // Upgrades from 0.9.3 have the ignore keys. Rebuild their labels as activity lists arrive.
    @Synchronized fun rememberIgnoredActivities(identity:String,activities:List<SignActivity>) {
        if(identity.isBlank())return
        val keys=prefs.getStringSet(ignoreField(identity),emptySet()).orEmpty()
        if(keys.isEmpty())return
        val records=ignoredDetails(identity)
        var changed=false
        for(item in activities) {
            if(item.kind!=ReminderKind.SIGN_IN)continue
            val key=accountKey(item.key)
            if(key in keys && !records.has(key)) {
                records.put(key,details(item))
                changed=true
            }
        }
        if(changed)prefs.edit().putString(ignoredDetailsField(identity),records.toString()).apply()
    }

    @Synchronized fun setIgnored(identity:String,activity:SignActivity,ignored:Boolean) {
        require(identity.isNotBlank() && activity.kind==ReminderKind.SIGN_IN)
        val field=ignoreField(identity)
        val keys=prefs.getStringSet(field,emptySet()).orEmpty()
        val key=accountKey(activity.key)
        val records=ignoredDetails(identity)
        if(ignored)records.put(key,details(activity)) else records.remove(key)
        prefs.edit().putStringSet(field,if(ignored)keys+key else keys-key)
            .putString(ignoredDetailsField(identity),records.toString()).apply()
        DiagnosticLog.event("SignDetection","account=${DiagnosticLog.reference(identity)} ${DiagnosticLog.activity(activity)} ignored=$ignored")
    }

    @Synchronized fun attempted(identity:String,activity:SignActivity):Boolean {
        val key=accountKey(activity.key)
        return key in prefs.getStringSet("sign.attempts.$identity",emptySet()).orEmpty() ||
            key in prefs.getStringSet("sign.confirmed.$identity",emptySet()).orEmpty()
    }
    /** Automatic preflight cooldown is independent of the final submission reservation. */
    @Synchronized fun allowAutomaticCheck(identity:String,activity:SignActivity):Boolean {
        if(ignored(identity,activity.key) || attempted(identity,activity))return false
        val field="sign.checks.$identity";val now=System.currentTimeMillis()
        val old=diagnosticRunCatching{JSONObject(prefs.getString(field,"{}").orEmpty())}.getOrDefault(JSONObject())
        val key=accountKey(activity.key)
        if(now-old.optLong(key,0) in 0 until 120_000)return false
        val recent=JSONObject()
        old.keys().asSequence().filter{now-old.optLong(it,0) in 0 until 120_000}.take(299).forEach{recent.put(it,old.get(it))}
        recent.put(key,now)
        return prefs.edit().putString(field,recent.toString()).commit()
    }
    fun reserve(identity: String, activity: SignActivity): Boolean = synchronized(submissionState) {
        val field = "sign.attempts.$identity"
        val previous = prefs.getStringSet(field, emptySet()).orEmpty()
        val key = accountKey(activity.key)
        if (key in previous) {DiagnosticLog.event("SignLedger", "${DiagnosticLog.activity(activity)} skipped reason=already_attempted");return@synchronized false}
        prefs.edit().putStringSet(field, (previous.toList().takeLast(999)+key).toSet()).commit()
    }
    /** Only a definitive server rejection may release a commit. Unknown replies remain reserved. */
    fun releaseRejected(identity:String,activity:SignActivity) = synchronized(submissionState) {
        val field="sign.attempts.$identity"
        val previous=prefs.getStringSet(field,emptySet()).orEmpty()
        val key=accountKey(activity.key)
        // A late rejection from an earlier request cannot erase confirmed success.
        if(key in prefs.getStringSet("sign.confirmed.$identity",emptySet()).orEmpty())return@synchronized
        if(key in previous && !prefs.edit().putStringSet(field,previous-key).commit())throw ApiProblem("未能保存拒绝结果，请先核对记录。")
        DiagnosticLog.event("SignLedger","account=${DiagnosticLog.reference(identity)} ${DiagnosticLog.activity(activity)} released=confirmed_rejection")
    }
    @Synchronized fun record(identity: String, activity: SignActivity, message: String) {
        DiagnosticLog.event("SignLedger", "account=${DiagnosticLog.reference(identity)} ${DiagnosticLog.activity(activity)} result=${DiagnosticLog.label(message)}")
        val key = "sign.records.$identity"
        val old = diagnosticRunCatching { JSONArray(prefs.getString(key,"[]")) }.getOrDefault(JSONArray())
        val row = JSONObject().put("time", System.currentTimeMillis()).put("course",activity.course.name).put("title",activity.title).put("message",message)
        val data = JSONArray().put(row)
        for (i in 0 until minOf(old.length(),19)) data.put(old.get(i))
        prefs.edit().putString(key,data.toString()).apply()
    }
    fun description(identity: String): String {
        val rows = diagnosticRunCatching { JSONArray(prefs.getString("sign.records.$identity","[]")) }.getOrDefault(JSONArray())
        return (0 until minOf(rows.length(),6)).joinToString("\n\n") { i -> rows.getJSONObject(i).let {
            "${AlertTrace.time(it.optLong("time"))} · ${it.optString("course")}\n${it.optString("title")} · ${it.optString("message")}" } }.ifBlank { "暂无提交记录。提交后请查看这里，并在学习通核对结果。" }
    }
}

