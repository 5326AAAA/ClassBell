package cn.classbell.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

object StudyUi {
    fun column(a:Activity)=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
    fun header(a:Activity,title:String,back:()->Unit):Pair<LinearLayout,TextView> {
        val bar=LinearLayout(a).apply{gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=ModernUi.dp(a,56)}
        bar.addView(ModernUi.navigation(a,action=back),LinearLayout.LayoutParams(ModernUi.dp(a,48),ModernUi.dp(a,48)))
        val label=text(a,title,20,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(ModernUi.dp(a,4),0,ModernUi.dp(a,8),0)}
        bar.addView(label,LinearLayout.LayoutParams(0,-2,1f));return bar to label
    }
    fun text(a:Activity,value:String,size:Int=14,bold:Boolean=false,color:Int=if(bold)ModernUi.ink else ModernUi.muted)=TextView(a).apply{
        text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(ModernUi.dp(a,3).toFloat(),1f)
        setPadding(0,ModernUi.dp(a,5),0,ModernUi.dp(a,5));if(bold)setTypeface(null,android.graphics.Typeface.BOLD)
    }
    fun button(a:Activity,value:String,primary:Boolean=false,action:()->Unit)=Button(a).apply{
        text=value;ModernUi.button(this,primaryAction=primary);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=ModernUi.dp(a,8)}
        setLoggedClickListener{action()}
    }
    fun card(a:Activity,parent:LinearLayout)=column(a).apply{
        setPadding(ModernUi.dp(a,16),ModernUi.dp(a,14),ModernUi.dp(a,16),ModernUi.dp(a,14));background=ModernUi.shape(a,Color.WHITE,20)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ModernUi.dp(a,12)})
    }
}

open class StudyActivity:Activity() {
    protected val app get()=application as BellApp
    protected open val requestedBoundAccountId:String? get()=null
    internal var boundAccountRepository:BoundAccounts?=null
    private val boundAccounts get()=boundAccountRepository ?: app.boundAccounts
    protected var boundSession:BoundAccount?=null
        private set
    protected val sessionOwner get()=boundSession?.id ?: owner
    protected val sessionCourses get()=boundSession?.courses ?: app.courses()
    protected val sessionCookies get()=boundSession?.cookies ?: app.cookies.snapshot()
    protected val sessionLabel get()=boundSession?.label ?: app.displayName().ifBlank{"当前账号"}
    protected lateinit var body:LinearLayout
    protected lateinit var state:TextView
    protected lateinit var titleView:TextView
    protected lateinit var pageRoot:LinearLayout
    protected lateinit var api:ChaoxingApi
    protected val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var task:Future<*>?=null
    protected val working: Boolean get() = task != null
    @Volatile protected var operation=0
    private var owner=""
    private var revision=0
    @Volatile private var closed=false
    private val changed:()->Unit={if(!valid()){cancelWork();finish()}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        boundSession=requestedBoundAccountId?.let { id->boundAccounts.list().firstOrNull{it.id==id} }
        owner=app.prefs.getString("identity","").orEmpty();revision=app.signSessionGeneration
        val cookies=SessionCookies(app.vault,null).apply{replaceWith(sessionCookies)};api=ChaoxingApi(cookies)
        val root=StudyUi.column(this).apply{setBackgroundColor(ModernUi.background);isFocusableInTouchMode=true};pageRoot=root
        root.setOnApplyWindowInsetsListener{v,i->@Suppress("DEPRECATION")
            v.setPadding(ModernUi.dp(this,18),i.systemWindowInsetTop+ModernUi.dp(this,8),ModernUi.dp(this,18),i.systemWindowInsetBottom);i}
        val header=StudyUi.header(this,"课程"){finish()};titleView=header.second;root.addView(header.first)
        state=StudyUi.text(this,"",12);root.addView(state)
        body=StudyUi.column(this)
        root.addView(ScrollView(this).apply{addView(body);isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false},LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        app.addUiListener(changed)
    }
    protected fun valid():Boolean {
        if(closed)return false
        if(requestedBoundAccountId!=null)return boundSession?.let{boundAccounts.current(it)}==true
        return owner.isNotBlank() && owner==app.prefs.getString("identity","") && revision==app.signSessionGeneration && app.prefs.getBoolean("loggedIn",false)
    }
    protected fun guard(){if(!valid() || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()}
    protected fun <T> work(message:String,action:()->T,done:(T)->Unit) {
        if(task?.isDone==false){state.text="正在处理，请稍候。";return}
        if(!valid()){state.text="请先在账号中心登录。";return}
        val token=++operation;state.text=message
        task=worker.submit {
            try{guard();val result=DiagnosticLog.operation("StudyTask",message){action()};if(!valid() || operation!=token){if(result is java.io.File)result.delete();return@submit};main.post{if(valid() && operation==token){task=null;state.text="";done(result)}else if(result is java.io.File)result.delete()}}
            catch (diagnosticError: java.util.concurrent.CancellationException) { DiagnosticLog.error("StudyUi", diagnosticError);}
            catch (e: Exception) { DiagnosticLog.error("StudyUi", e);main.post{if(!closed && operation==token){task=null;state.text=if(e is ApiProblem || e is LoginRequired)e.message else "操作未完成，请稍后重试。"}}}
        }
    }
    protected fun cancelWork(){operation++;api.cancelRequests();task?.cancel(true);task=null}
    override fun onDestroy(){closed=true;cancelWork();app.removeUiListener(changed);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
}
