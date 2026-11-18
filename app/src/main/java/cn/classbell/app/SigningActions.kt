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

class SigningActions(private val app: BellApp, private val createSource: (() -> Unit) -> SignSource = { SigningApi(app.cookies,it) }) {
    @Volatile private var automaticSource:SignSource?=null
    fun cancelAutomatic() { diagnosticRunCatching { automaticSource?.cancel() } }
    val ledger = SignLedger(app.prefs)
    fun source(extraGuard: () -> Unit = {}): SignSource {
        val generation = app.signSessionGeneration
        val identity = app.prefs.getString("identity", "").orEmpty()
        return createSource {
            if (Thread.currentThread().isInterrupted || generation != app.signSessionGeneration || identity.isBlank() ||
                identity != app.prefs.getString("identity", "") || !app.prefs.getBoolean("loggedIn",false)) throw ApiProblem("账号已变化或操作已取消，未继续提交。")
            extraGuard()
        }
    }
    @Synchronized fun autoSubmit(activity: SignActivity, active: () -> Boolean) {
        val identity = app.prefs.getString("identity", "").orEmpty()
        val revision=app.signSessionGeneration
        fun enabled() = active() && app.prefs.getBoolean("loggedIn",false) && identity==app.prefs.getString("identity","") &&
            revision==app.signSessionGeneration && !ledger.ignored(identity,activity.key) &&
            (app.prefs.getBoolean("autoNormal.$identity", false) || app.prefs.getBoolean("autoPhoto.$identity", false)) &&
            activity.course.key in app.selectedCourses().map { it.key }
        if (identity.isBlank() || activity.kind != ReminderKind.SIGN_IN || activity.otherId != 0 || !enabled() || !ledger.allowAutomaticCheck(identity,activity)) return
        val source = source { if (!enabled()) throw ApiProblem("自动签到已停止。") }
        automaticSource=source
        ledger.record(identity, activity, "正在确认签到要求；若中途退出请在学习通核对。")
        app.notifyUi()
        var photo:File?=null
        val result = try {
            val plan = source.plan(activity)
            val photoVersion=app.savedSignPhotos.version(identity)
            val inputs=when {
                plan.mode==SignMode.NORMAL && app.prefs.getBoolean("autoNormal.$identity",false) -> SignInputs(expectedMode=plan.mode)
                plan.mode==SignMode.PHOTO && app.prefs.getBoolean("autoPhoto.$identity",false) -> {
                    photo=app.savedSignPhotos.lease(identity) ?: throw ApiProblem("尚未预选照片，保留提醒，可手动选择照片提交。")
                    SignInputs(photo=photo,expectedMode=plan.mode)
                }
                else -> null
            }
            if(inputs==null)SignOutcome(false,"未自动提交：${plan.message}",SignResultCategory.NOT_SUBMITTED)
            else SignSubmission(ledger).submit(identity,activity,source,inputs) {
                if(!enabled())throw ApiProblem("自动签到已停止。")
                val currentMode=if(inputs.photo==null)"autoNormal" else "autoPhoto"
                if(!app.prefs.getBoolean("$currentMode.$identity",false))throw ApiProblem("该类型自动签到已停止。")
                if(inputs.photo!=null && photoVersion!=app.savedSignPhotos.version(identity))throw ApiProblem("照片已更换，请重新核对。")
            }

        } catch (e: ApiProblem) { DiagnosticLog.error("Signing", e); SignOutcome(false,e.message ?: "未完成，请到学习通核对。") }
        catch (diagnosticError: LoginRequired) { DiagnosticLog.error("Signing", diagnosticError); SignOutcome(false,"登录已失效，未继续提交。") }
        catch (diagnosticError: Exception) { DiagnosticLog.error("Signing", diagnosticError); SignOutcome(false,"请求中断或结果未知；不自动重试，请到学习通核对。") }
        finally { photo?.delete();source.cancel();automaticSource=null }
        ledger.record(identity, activity, result.message)
        app.notifyUi()
    }
}
