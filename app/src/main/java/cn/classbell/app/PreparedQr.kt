package cn.classbell.app

import android.os.SystemClock
import org.json.JSONObject

/** Metadata is not a prediction of how much validity remains in a scanned frame. */
data class QrRefreshInfo(val enabled:Boolean?=null,val seconds:Int?=null) {
    fun description()=when(enabled) {
        true->seconds?.let{"二维码每 $it 秒更新；识别后立即提交"} ?: "二维码动态更新；识别后立即提交"
        false->"二维码未开启自动更新"
        null->"二维码刷新周期未提供；识别后立即提交"
    }
    companion object {
        fun read(data:JSONObject):QrRefreshInfo {
            val flags=listOf("ifrefreshewm","ifRefreshEwm").mapNotNull { key->
                if(data.isNull(key))null else when(data.optString(key).lowercase()) {"1","true"->true;"0","false"->false;else->null}
            }.distinct()
            val times=listOf("ewmRefreshTime","ewmrefreshtime").mapNotNull { key->
                if(data.isNull(key))null else data.optString(key).toIntOrNull()?.takeIf{it in 1..3600}
            }.distinct()
            return QrRefreshInfo(flags.singleOrNull(),times.singleOrNull())
        }
    }
}

/** Account checks and network preparation finish before opening the camera. No QR is persisted. */
class PreparedQr internal constructor(
    internal val source:SignSource,
    private val item:SignActivity,
    val plan:SignPlan,
    private val send:(SignQr,LocationFix?,()->Unit)->SignOutcome
) {
    private val preparedAt=SystemClock.elapsedRealtime()
    fun current()=SystemClock.elapsedRealtime()-preparedAt in 0..120_000 &&
        plan.overview?.endsAt?.let{it>System.currentTimeMillis()}!=false
    internal fun submit(api:SignSource,activity:SignActivity,qr:SignQr,fix:LocationFix?,beforeCommit:()->Unit):SignOutcome {
        if(api!==source || activity.key!=item.key)throw ApiProblem("扫码准备与账号或活动不一致，请重新准备。")
        if(!current())throw ApiProblem("扫码准备已超时，请重新准备后扫描新码。")
        qr.requireFor(activity)
        qr.requireFresh()
        if(plan.requiresLocation)(fix ?: throw ApiProblem("请先更新本次手机定位，再扫码。")).requireCurrent()
        return send(qr,fix) { qr.requireFresh();beforeCommit() }
    }
}

internal class QrDispatchTrace(val capturedAt:Long)
