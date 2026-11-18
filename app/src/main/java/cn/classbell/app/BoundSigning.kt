package cn.classbell.app

import okhttp3.Cookie

class BoundSigning(private val accounts:BoundAccounts,private val ledger:SignLedger,
    private val factory:(List<Cookie>,()->Unit)->SignSource={cookies,guard->SigningApi(cookies,guard,allowCode=true)}) {
    fun source(account:BoundAccount,extraGuard:()->Unit={}):SignSource = factory(account.cookies.toList()) {
        if(Thread.currentThread().isInterrupted || !accounts.current(account))throw ApiProblem("账号已解绑、更新或操作已取消。")
        extraGuard()
    }
    fun submit(account:BoundAccount,activity:SignActivity,code:String,api:SignSource,guard:()->Unit):SignOutcome {
        guard()
        if(!accounts.current(account) || activity.course.key !in account.courses.map{it.key} || activity.kind!=ReminderKind.SIGN_IN)
            throw ApiProblem("签到不属于所选账号的课程，未提交。")
        fun commitGuard() {guard();if(!accounts.current(account))throw ApiProblem("账号已解绑或更新，未继续提交。")}
        return SignSubmission(ledger).submit(account.id,activity,api,SignInputs(code=code),::commitGuard)
    }
}
