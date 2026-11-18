package cn.classbell.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

class BoundAccountsActivity:Activity() {
    private val app get()=application as BellApp
    internal var accountRepository:BoundAccounts?=null
    private val accounts get()=accountRepository ?: app.boundAccounts
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private lateinit var tabs:LinearLayout
    private lateinit var cancelButton:Button
    @Volatile private var generation=0
    private var task:Future<*>?=null
    @Volatile private var source:SignSource?=null
    @Volatile private var loginApi:ChaoxingApi?=null
    private var proxy=false
    private var selected:BoundAccount?=null
    private var course:Course?=null
    private var found=emptyList<SignActivity>()
    private var busy=false
    private var closed=false
    private val signing by lazy{BoundSigning(accounts,app.signing.ledger)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=column().apply{setBackgroundColor(ModernUi.background)}
        root.setOnApplyWindowInsetsListener{view,insets-> @Suppress("DEPRECATION")
            view.setPadding(dp(18),insets.systemWindowInsetTop+dp(10),dp(18),insets.systemWindowInsetBottom);insets}
        root.addView(StudyUi.header(this,"关联账号"){finish()}.first)
        root.addView(text("账号由你管理 · 会话在本机加密保存",12))
        tabs=LinearLayout(this);root.addView(tabs)
        status=text("准备好后，选择账号和课程。",13);root.addView(status)
        cancelButton=button("取消当前操作"){cancelWork();status.text="操作已取消；已发出的提交请在学习通核对。"}.apply{visibility=android.view.View.GONE};root.addView(cancelButton)
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false};body=column();scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);render()
    }
    private fun render() {
        tabs.removeAllViews()
        for((label,value) in listOf("绑定账号" to false,"代签" to true))tabs.addView(button(label){cancelWork();proxy=value;found=emptyList();render()}.apply{
            ModernUi.button(this,primaryAction=proxy==value)
        },LinearLayout.LayoutParams(0,-2,1f).apply{if(tabs.childCount==1)marginStart=dp(8)})
        body.removeAllViews()
        if(proxy)proxyPage() else bindingPage()
    }
    private fun bindingPage() {
        val values=accounts.list()
        body.addView(text("已绑定 ${values.size} / ${BoundAccounts.MAX_ACCOUNTS}",18,true))
        if(values.isEmpty())body.addView(text("还没有绑定账号。可以添加已获授权的账号，或保存当前登录。",14))
        for(account in values) {
            val box=card();box.addView(text(account.label,17,true));box.addView(text("${account.courses.size} 门课程 · 独立登录会话",12))
            if(app.savedCredentials.read(account.id)!=null)box.addView(button("不再保存账号和密码"){app.forgetCredentials(account.id);status.text="已清除保存的账号和密码，登录状态保留。";render()})
            box.addView(button("使用此账号代签"){cancelWork();selected=account;course=null;found=emptyList();proxy=true;render()})
            box.addView(button("解绑 ${account.label}"){
                ModernUi.dialog(this).setTitle("解绑账号").setMessage("移除“${account.label}”的本机登录会话，不影响其他账号。")
                    .setPositiveButton("解绑"){_,_->cancelWork();app.forgetCredentials(account.id);accounts.unbind(account.id);if(selected?.id==account.id){selected=null;course=null};render()}
                    .setNegativeButton("取消",null).show()
            })
        }
        val form=card();form.addView(text("添加已授权账号",18,true))
        val name=input("备注，如：我的另一个账号");form.addView(name)
        val account=input("手机号 / 通用学习通账号");form.addView(account)
        val password=input("学习通密码",true);form.addView(password)
        val rememberCredentials=CheckBox(this).apply { text="保存账号和密码";ModernUi.selection(this);form.addView(this) }
        form.addView(text("勾选后在本机加密保存；不勾选只保留登录状态。遇验证码或学校认证，可在账号中心使用官方网页登录。",12))
        form.addView(button("登录并绑定"){
            if(busy)return@button
            val username=account.text.toString().trim();val secret=password.text.toString();val label=name.text.toString().trim().ifBlank{"账号 · ${username.takeLast(4)}"}
            if(username.isBlank() || secret.isBlank()){status.text="请填写账号和密码。";return@button}
            val keepPassword=rememberCredentials.isChecked
            password.text.clear()
            work("正在登录并核对课程…",{guard->
                val cookies=SessionCookies(app.vault,null)
                val api=ChaoxingApi(cookies);loginApi=api
                try {val courses=api.login(username,secret);guard();Triple(label,cookies.snapshot(),courses)}finally{api.cancelRequests();loginApi=null}
            }) {value->
                val bound=accounts.bind(value.first,value.second,value.third)
                app.savedCredentials.save(bound.id,username,secret,keepPassword)
                status.text=if(keepPassword)"账号已绑定，账号和密码已在本机加密保存。" else "账号已绑定，账号和密码未保存。"
                render()
            }
        })
        form.addView(button("保存当前已登录账号"){
            if(!app.prefs.getBoolean("loggedIn",false)){status.text="请先在“我的 → 账号中心”完成登录，再返回保存。";return@button}
            val owner=app.prefs.getString("identity","");val snapshot=app.cookies.snapshot();val label=name.text.toString().ifBlank{"当前登录账号"}
            work("正在核对当前账号…",{guard->val jar=SessionCookies(app.vault,null).apply{replaceWith(snapshot)};val api=ChaoxingApi(jar)
                try{api.profile();guard();if(owner!=app.prefs.getString("identity",""))throw java.util.concurrent.CancellationException();snapshot}finally{api.cancelRequests()}
            }){value->accounts.bind(label,value,app.courses());status.text="已保存当前账号的独立会话。";render()}
        })
    }
    private fun proxyPage() {
        val accounts=this.accounts.list()
        selected=selected?.let{old->accounts.firstOrNull{it.id==old.id && it.revision==old.revision}}
        val chosen=selected
        val box=card();box.addView(text("为已授权账号操作",18,true))
        box.addView(text("选择已授权账号及其课程，在应用内核对签到要求和结果。位置签到暂不可用。",13))
        box.addView(button("选择账号 · ${chosen?.label ?: "未选择"}"){
            if(accounts.isEmpty()){status.text="请先绑定账号。";return@button}
            ModernUi.dialog(this).setTitle("选择操作账号").setItems(accounts.map{it.label}.toTypedArray()){_,index->
                cancelWork();selected=accounts[index];course=null;found=emptyList();render()
            }.setNegativeButton("取消",null).show()
        })
        if(chosen==null)return
        box.addView(button("选择课程 · ${course?.name ?: "未选择"}"){
            ModernUi.dialog(this).setTitle("${chosen.label}的课程").setItems(chosen.courses.map{it.name}.toTypedArray()){_,i->
                cancelWork();course=chosen.courses[i];found=emptyList();render()
            }.setNegativeButton("取消",null).show()
        })
        box.addView(button("查询该课程进行中的签到"){
            val target=course ?: run{status.text="请先选择课程。";return@button}
            work("正在查询所选账号的签到…",{guard->withSource(chosen,guard){it.activities(target).filter{a->a.kind==ReminderKind.SIGN_IN}.take(30)}}) {
                found=it;status.text=if(it.isEmpty())"当前没有进行中的签到。" else "查询到 ${it.size} 项签到，请检查并确认后提交。";render()
            }
        })
        for(item in found) {
            val row=card();row.addView(text(item.title,17,true));row.addView(text("${chosen.label} · ${item.course.name}",13))
            row.addView(button("查看要求并处理签到"){
                if(!busy)startActivity(SignActionActivity.intent(this,item,boundAccountId=chosen.id))
            })
            row.addView(button("选择多个账号处理这项签到"){
                if(!busy)startActivity(MultiAccountSignActivity.intent(this,item))
            })

        }
        body.addView(text("该账号的最近结果",16,true));body.addView(text(app.signing.ledger.description(chosen.id),12))
    }
    private fun <T> withSource(account:BoundAccount,guard:()->Unit,action:(SignSource)->T):T {
        val api=signing.source(account,guard);source=api
        try {guard();return action(api)}finally{api.cancel();source=null}
    }
    private fun <T> work(message:String,action:(()->Unit)->T,done:(T)->Unit) {
        if(busy){status.text="已有操作正在进行，请等待或取消。";return}
        val token=++generation;busy=true;cancelButton.visibility=android.view.View.VISIBLE;status.text=message
        fun guard(){if(closed || token!=generation || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()}
        task=worker.submit {
            try {val result=DiagnosticLog.operation("BoundAccountTask", "action=${DiagnosticLog.label(message)} account=${DiagnosticLog.reference(selected?.id.orEmpty())} course=${DiagnosticLog.label(course?.name.orEmpty())}"){action(::guard)};guard();main.post{if(!closed && token==generation){busy=false;cancelButton.visibility=android.view.View.GONE;task=null;try{done(result)}catch (diagnosticError: Exception) { DiagnosticLog.error("BoundAccountsActivity", diagnosticError);status.text="本机保存未完成，请重试。"}}}}
            catch (diagnosticError: java.util.concurrent.CancellationException) { DiagnosticLog.error("BoundAccountsActivity", diagnosticError);}
            catch (error: Exception) { DiagnosticLog.error("BoundAccountsActivity", error);main.post{if(!closed && token==generation){busy=false;cancelButton.visibility=android.view.View.GONE;task=null;status.text=if(error is ApiProblem || error is LoginRequired)error.message else "请求未完成，请检查网络后重试。"}}}
        }
    }
    private fun cancelWork(){generation++;source?.cancel();loginApi?.cancelRequests();task?.cancel(true);task=null;busy=false;if(::cancelButton.isInitialized)cancelButton.visibility=android.view.View.GONE}
    override fun onDestroy(){closed=true;cancelWork();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun dp(value:Int)=ModernUi.dp(this,value)
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun card()=column().apply{setPadding(dp(16),dp(14),dp(16),dp(14));background=ModernUi.shape(this@BoundAccountsActivity,Color.WHITE,22);body.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})}
    private fun text(value:String,size:Int,bold:Boolean=false)=TextView(this).apply{text=value;textSize=size.toFloat();setTextColor(if(bold)ModernUi.ink else ModernUi.muted);setPadding(0,dp(5),0,dp(5));if(bold)setTypeface(null,android.graphics.Typeface.BOLD)}
    private fun button(value:String,action:()->Unit)=Button(this).apply{text=value;ModernUi.button(this);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)};setLoggedClickListener{action()}}
    private fun input(hintValue:String,password:Boolean=false)=EditText(this).apply{hint=hintValue;inputType=InputType.TYPE_CLASS_TEXT or if(password)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL;ModernUi.input(this);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)};if(password)importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS}
}
