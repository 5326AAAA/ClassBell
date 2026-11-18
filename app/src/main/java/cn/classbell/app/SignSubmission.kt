package cn.classbell.app

import java.io.File

/** Inputs remain in memory. Do not add a data-class toString containing sign codes or coordinates. */
class SignInputs(val code:String="",val photo:File?=null,val fix:LocationFix?=null,val point:ManualLocation?=null,val expectedMode:SignMode?=null,val qr:SignQr?=null,val gesture:String="")

/** Shared by current-account and bound-account forms; reserve only at the actual sign commit. */
class SignSubmission(private val ledger:SignLedger) {
    fun submit(owner:String,item:SignActivity,api:SignSource,inputs:SignInputs,guard:()->Unit):SignOutcome =
        DiagnosticLog.operation("SignSubmission","account=${DiagnosticLog.reference(owner)} ${DiagnosticLog.activity(item)}") {
            guard()
            if(ledger.attempted(owner,item))return@operation SignOutcome(false,"已有提交记录，请先核对上次结果。",SignResultCategory.ALREADY_ATTEMPTED)
            var committed=false
            var duplicate=false
            fun beforeCommit() {
                guard()
                if(!committed) {
                    if(!ledger.reserve(owner,item)){duplicate=true;throw ApiProblem("已有提交记录，请先核对上次结果。")}
                    committed=true
                    DiagnosticLog.event("SignSubmission","${DiagnosticLog.activity(item)} commit_reserved=true")
                }
            }
            val result=try {
                guard()
                val plan=api.plan(item)
                if(plan.mode!=SignMode.OFFICIAL && inputs.expectedMode!=null && inputs.expectedMode!=plan.mode)throw ApiProblem("签到要求已变化，请重新核对并确认，尚未提交。")
                when(plan.mode) {
                    SignMode.OFFICIAL->SignOutcome(false,plan.message,if(plan.repairNeeded)SignResultCategory.NEEDS_FIX else SignResultCategory.NOT_SUBMITTED)
                    SignMode.PHOTO->{if(inputs.photo?.isFile!=true)throw ApiProblem("请先选择本次照片。");api.submit(item,inputs.photo,::beforeCommit)}
                    SignMode.CODE->{if(!Regex("[0-9]{4,16}").matches(inputs.code))throw ApiProblem("请输入老师公布的签到码。");api.submitCode(item,inputs.code,::beforeCommit)}
                    SignMode.QR->{val qr=inputs.qr ?: throw ApiProblem("请先扫描本次签到二维码。");qr.requireFor(item);api.submitQr(item,qr,inputs.fix,::beforeCommit)}
                    SignMode.GESTURE->{GesturePath.requireValid(inputs.gesture);api.submitGesture(item,inputs.gesture,::beforeCommit)}
                    SignMode.LOCATION->when {
                        inputs.point!=null->{inputs.point.requireValid();api.submitManualLocation(item,inputs.point,::beforeCommit)}
                        inputs.fix!=null->{inputs.fix.requireCurrent();api.submitLocation(item,inputs.fix,::beforeCommit)}
                        else->throw ApiProblem("请先获取并核对本次位置。")
                    }
                    SignMode.NORMAL->api.submit(item,beforeCommit=::beforeCommit)
                }
            } catch(e:java.util.concurrent.CancellationException) {
                if(!committed)throw e
                SignOutcome(false,"提交后操作取消，结果待核实，不自动重试。",SignResultCategory.UNKNOWN)
            } catch(e:Exception) {
                DiagnosticLog.error("SignSubmission",e)
                when {
                    e is PhotoTransferException->SignOutcome(false,
                        "${e.photoState.label}；" + if(duplicate)"已有其他提交记录，本次未重复提交，请核对结果。" else if(committed)"签到请求已发出，结果待核实，不自动重试。" else
                            "签到尚未提交。"+(if(e.cause is ApiProblem || e.cause is LoginRequired)e.cause?.message.orEmpty() else "请稍后核对再处理。"),
                        when { duplicate->SignResultCategory.ALREADY_ATTEMPTED; committed->SignResultCategory.UNKNOWN; else->SignResultCategory.NOT_SUBMITTED },photoState=e.photoState)
                    duplicate->SignOutcome(false,e.message.orEmpty(),SignResultCategory.ALREADY_ATTEMPTED)
                    committed->SignOutcome(false,"提交已发出但结果未确认，不自动重试；请核实后再处理。",SignResultCategory.UNKNOWN)
                    e is LoginRequired->SignOutcome(false,"所选账号登录已失效，请重新登录或绑定。",SignResultCategory.NOT_SUBMITTED)
                    e is ApiProblem->SignOutcome(false,e.message.orEmpty(),SignResultCategory.NOT_SUBMITTED)
                    else->SignOutcome(false,"准备阶段失败，尚未提交签到，可重试。",SignResultCategory.NOT_SUBMITTED)
                }
            }
            if(committed && !result.successful && result.retryAllowed && result.category==SignResultCategory.FAILED)ledger.releaseRejected(owner,item)
            ledger.record(owner,item,"${result.category.label} · ${result.message}")
            result
        }
}
