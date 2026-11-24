package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One activity, isolated account snapshots, bounded QR concurrency and a result for every account. */
class MultiAccountSignActivity:Activity() {
    private val app get()=application as BellApp
    internal var accountRepository:BoundAccounts?=null
    internal var sourceFactory:((BoundAccount,()->Unit)->SignSource)?=null
    // Tests can exercise the real page without writing real account submission records.
    internal var ledgerRepository:SignLedger?=null
    private val ledger get()=ledgerRepository ?: app.signing.ledger
    private val accountStore get()=accountRepository ?: app.boundAccounts
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var body:LinearLayout
    private lateinit var state:TextView
    private lateinit var location:DeviceLocation
    private lateinit var item:SignActivity
    private var accounts=emptyList<BoundAccount>()
    private val selected=linkedSetOf<String>()
    private val plans=linkedMapOf<String,SignPlan>()
    private enum class Step { CHECK, QUERY, SUBMIT }
    private data class StepResult(val plan:SignPlan?=null,val outcome:SignOutcome?=null,val message:String?=null)
    private val submitResults=linkedMapOf<String,SignOutcome>()
    private val queryResults=linkedMapOf<String,SignOutcome>()
    private val confirmed=linkedMapOf<String,SignOutcome>()
    private val notes=linkedMapOf<String,String>()
    private val photos=linkedMapOf<String,File>()
    private val previews=linkedMapOf<String,android.graphics.Bitmap>()
    private var code=""
    private var gesture=""
    private var qr:SignQr?=null
    private val preparedSources=java.util.concurrent.ConcurrentHashMap<String,SignSource>()
    private val preparedTickets=java.util.concurrent.ConcurrentHashMap<String,PreparedQr>()
    private var scanningAccounts=emptyList<String>()
    private var autoQrLoop=false
    private var foreground=false
    private var rejectedQrEnc:String?=null
    private var lastQrEnc:String?=null
    private var fix:LocationFix?=null
    private var picking:String?=null
    @Volatile private var generation=0
    @Volatile private var closed=false
    private val activeSources=java.util.concurrent.ConcurrentHashMap.newKeySet<SignSource>()
    private var task:Future<*>?=null
    private var busy=false
    private var dialog:android.app.AlertDialog?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val courseKey=intent.getStringExtra("courseKey")
        accounts=accountStore.list().filter { a->a.courses.any{it.key==courseKey} }
        val course=accounts.firstOrNull()?.courses?.firstOrNull{it.key==courseKey}
        val id=intent.getStringExtra("activeId").orEmpty()
        if(course==null || !SignProtocol.validId(id)) {
            Toast.makeText(this,"没有绑定账号属于这门课程，请先绑定或更新课程。",Toast.LENGTH_LONG).show();finish();return
        }
        item=SignActivity(id,intent.getStringExtra("title").orEmpty(),course,otherId=intent.getIntExtra("otherId",-1).takeIf{it>=0})
        val root=StudyUi.column(this).apply{setBackgroundColor(ModernUi.background)}
        root.setOnApplyWindowInsetsListener{view,insets->@Suppress("DEPRECATION")
            view.setPadding(ModernUi.dp(this,18),insets.systemWindowInsetTop,ModernUi.dp(this,18),insets.systemWindowInsetBottom);insets}
        root.addView(StudyUi.header(this,"多账号签到"){finish()}.first)
        state=StudyUi.text(this,"请选择账号。");root.addView(state)
        body=StudyUi.column(this);root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);location=DeviceLocation(this);render()
    }
    private fun render() {
        body.removeAllViews()
        val intro=StudyUi.card(this,body)
        intro.addView(StudyUi.text(this,item.course.name,20,true));intro.addView(StudyUi.text(this,item.title))
        intro.addView(StudyUi.text(this,"选择账号后，可以核对签到要求或刷新各账号的结果。",13))
        val selectedCount=StudyUi.text(this,"已选 ${selected.size} 个账号",13,true)
        intro.addView(selectedCount)
        val checkButton=StudyUi.button(this,"核对所选账号的要求",true){check()}
        val resultButton=StudyUi.button(this,"刷新所选账号的签到结果"){refreshResults()}
        val submitButton=StudyUi.button(this,"确认所选账号并提交",true){confirm()}
        fun updateActions() {
            selectedCount.text="已选 ${selected.size} 个账号"
            checkButton.isEnabled=!busy && selected.isNotEmpty()
            resultButton.isEnabled=checkButton.isEnabled
            submitButton.isEnabled=checkButton.isEnabled && selected.all{it in plans} &&
                selected.any{plans[it]?.mode != SignMode.OFFICIAL && !locked(it)}
        }
        updateActions()
        if(busy)intro.addView(StudyUi.button(this,"停止后续账号"){cancel();state.text="已停止后续处理。已发出的提交请查看记录并核对。";render()})
        for(account in accounts) {
            val row=StudyUi.card(this,body)
            row.addView(CheckBox(this).apply {
                text=account.label;ModernUi.selection(this);isChecked=account.id in selected;isEnabled=!busy
                setOnCheckedChangeListener{_,checked->
                    if(checked) selected.add(account.id) else selected.remove(account.id)
                    updateActions()
                }
            })
            val id=account.id
            val submission=submitResults[id]
            val query=queryResults[id]
            val outcome=when {
                confirmed[id]!=null->confirmed[id]
                submission!=null->submission
                locked(id)->null
                else->query
            }
            val plan=plans[id]
            val headline=outcome?.let{"${it.category.label} · ${it.message}"}
                ?: if(locked(id)) "结果待核实 · 已有提交记录，请刷新结果。" else plan?.message ?: "尚未核对"
            row.addView(StudyUi.text(this,headline,13,true,if(outcome?.successful==true)ModernUi.strongPrimary else ModernUi.ink))
            val photoState=submission?.photoState ?: PhotoTransferState.NOT_SENT
            if(photoState!=PhotoTransferState.NOT_SENT)row.addView(StudyUi.text(this,photoState.label,12))
            if(query!=null && query!==outcome && !query.successful)row.addView(StudyUi.text(this,"最近查询：${query.message}",12))
            notes[id]?.let{row.addView(StudyUi.text(this,it,12))}
            plan?.requirements?.lines?.takeIf{it.isNotEmpty()}?.let{row.addView(StudyUi.text(this,it.joinToString("\n"),12))}
            if(plan?.repairNeeded==true)row.addView(StudyUi.text(this,if(plan.issueCode=="LOCATION_DISABLED")"不可用" else "待修复",12,true).apply{setTextColor(ModernUi.danger)})
            if(plan?.mode==SignMode.PHOTO) {
                row.addView(StudyUi.text(this,SignPhoto.PRIVACY_NOTICE,12))
                previews[account.id]?.let { bitmap->row.addView(ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="${account.label} 的本次签到照片"},LinearLayout.LayoutParams(-1,ModernUi.dp(this,180))) }
                row.addView(StudyUi.text(this,if(photos[account.id]?.isFile==true)"本次照片已准备" else "尚未选择本次照片",12))
                row.addView(StudyUi.button(this,"为 ${account.label} 选择照片"){
                    if(!busy){picking=account.id;try {
                        SignPhoto.openPicker(this,PHOTO)
                    }catch(e:Exception){DiagnosticLog.error("BatchPhotoPicker",e);state.text="无法打开图片选择器。"}}
                }.apply{isEnabled=!busy})
                if(photos[account.id]?.isFile==true)row.addView(StudyUi.button(this,"移除 ${account.label} 的本次照片"){
                    if(!busy){photos.remove(account.id)?.delete();previews.remove(account.id)?.recycle();state.text="本次照片已移除，未上传。";render()}
                }.apply{isEnabled=!busy})
            }
            row.addView(StudyUi.button(this,"单独处理此账号"){
                if(!busy)startActivity(SignActionActivity.intent(this,item,boundAccountId=account.id))
            }.apply{isEnabled=!busy})
        }
        val controls=StudyUi.card(this,body)
        controls.addView(checkButton)
        controls.addView(resultButton)
        if(plans.values.any{it.mode==SignMode.CODE})controls.addView(EditText(this).apply {
            hint="老师公布的本次签到码";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(code);ModernUi.input(this);isEnabled=!busy
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){code=s?.toString().orEmpty()}
                override fun afterTextChanged(s:android.text.Editable?){}
            })
        })
        if(plans.values.any{it.mode==SignMode.GESTURE})controls.addView(gestureInput(this,gesture,!busy){gesture=it})
        if(plans.values.any{it.mode==SignMode.QR}) {
            controls.addView(StudyUi.text(this,if(qr==null)"先选择账号并准备位置；识别当前码后，所选账号同时提交，无需再次确认。" else "已识别本次二维码，各账号使用自己的会话提交。码过期时请重扫；成功账号不会重复提交。",13))
            controls.addView(StudyUi.button(this,"扫码并立即提交所选账号",true){
                val bound=chosen().firstOrNull() ?: return@button
                if(!busy)prepareAndScan()
            }.apply{isEnabled=!busy})
        }
        if(plans.values.any{it.mode==SignMode.LOCATION || it.requiresLocation}) {
            controls.addView(StudyUi.text(this,fix?.let{"本次手机定位：${it.latitude}, ${it.longitude}，精度约 ${it.accuracy.toInt()} 米"} ?: "位置签到需获取本次手机定位；也可单独处理账号。",12))
            controls.addView(StudyUi.button(this,"更新本次手机定位"){locate()}.apply{isEnabled=!busy})
        }
        controls.addView(submitButton)
    }
    private fun chosen()=accounts.filter{it.id in selected}
    private fun locked(id:String)=id in confirmed || submitResults[id]?.locksSignSubmission==true || ledger.attempted(id,item)
    private fun skipReason(account:BoundAccount):String? = when {
        !accountStore.current(account)->"账号已更新或解绑"
        account.id in confirmed->"已确认签到"
        locked(account.id)->"已有提交记录，请先刷新结果"
        plans[account.id]?.mode==SignMode.OFFICIAL->"当前签到要求不支持应用内处理"
        else->null
    }
    private fun guard(account:BoundAccount,token:Int) {
        if(closed || token!=generation || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
        if(!accountStore.current(account))throw ApiProblem("账号已更新或解绑，请返回重新进入。")
    }
    private fun check() {
        val chosen=chosen();if(chosen.isEmpty()){state.text="请先选择账号。";return}
        chosen.forEach{plans.remove(it.id);notes.remove(it.id)}
        runAccounts(chosen,"正在逐个核对要求…",Step.CHECK) {_,api,_->
            StepResult(plan=api.plan(item))
        }
    }
    private fun refreshResults(automatic:Boolean=false) {
        val chosen=chosen().filter{!automatic || (it.id !in confirmed && submitResults[it.id]?.category==SignResultCategory.UNKNOWN)}
        if(chosen.isEmpty())return
        runAccounts(chosen,"正在逐个查询签到结果…",Step.QUERY,parallel=true,onComplete={if(autoQrLoop && foreground)continueQrScan()}) { account,api,guard ->
            val outcome=api.attendance(item)
            guard()
            if(outcome.successful) {
                ledger.confirmSuccess(account.id,item)
                ledger.record(account.id,item,"结果查询 · ${outcome.message}")
            }
            StepResult(outcome=outcome)
        }
    }
    private fun confirm() {
        val chosen=chosen();if(chosen.isEmpty() || busy || dialog?.isShowing==true)return
        if(chosen.any{plans[it.id]==null}){state.text="请先核对全部所选账号的要求。";return}
        val choices=chosen.associateWith(::skipReason)
        val ready=chosen.filter{choices[it]==null}
        if(ready.isEmpty()){state.text="所选账号没有可提交的签到：${choices.values.filterNotNull().distinct().joinToString("；")}。";return}
        if(ready.all{plans[it.id]?.mode==SignMode.QR}){prepareAndScan();return}
        if(ready.any{plans[it.id]?.mode==SignMode.PHOTO && photos[it.id]?.isFile!=true}){state.text="请分别选择需要照片的账号的本次照片。";return}
        if(ready.any{plans[it.id]?.mode==SignMode.CODE} && !Regex("[0-9]{4,16}").matches(code)){state.text="请填写老师公布的签到码。";return}
        if(ready.any{plans[it.id]?.mode==SignMode.QR} && qr?.activityId!=item.id){state.text="请先扫描本次签到的当前二维码。";return}
        if(ready.any{plans[it.id]?.mode==SignMode.GESTURE} && !GesturePath.valid(gesture)){state.text="请按老师公布的顺序输入手势。";return}
        if(ready.any{plans[it.id]?.mode==SignMode.LOCATION || plans[it.id]?.requiresLocation==true} && diagnosticRunCatching{fix?.requireCurrent() ?: throw ApiProblem("未定位")}.isFailure){state.text="请先更新本次手机定位。";return}
        val selectedQr=qr;val selectedGesture=gesture;val selectedCode=code;val selectedFix=fix;val selectedPhotos=photos.toMap();val selectedModes=plans.mapValues{it.value.mode}
        dialog=ModernUi.dialog(this).setTitle("确认本次签到")
            .setView(confirmationPanel(choices))
            .setNegativeButton("取消",null).setPositiveButton("提交所选账号一次"){_,_->
                choices.forEach{(account,reason)->if(reason!=null)notes[account.id]=reason}
                runAccounts(ready,"正在提交所选账号…",Step.SUBMIT,parallel=ready.all{selectedModes[it.id]==SignMode.QR}) {account,api,check->
                    val outcome=SignSubmission(ledger).submit(account.id,item,api,
                        SignInputs(code=selectedCode,photo=selectedPhotos[account.id],fix=selectedFix,expectedMode=selectedModes[account.id],qr=selectedQr,gesture=selectedGesture),check)
                    StepResult(outcome=outcome)
                }
            }.create().apply{setOnDismissListener{dialog=null};show()}
    }
    private fun confirmationPanel(choices:Map<BoundAccount,String?>):ScrollView {
        val activity=this
        val content=StudyUi.column(activity).apply{setPadding(ModernUi.dp(activity,20),0,ModernUi.dp(activity,20),ModernUi.dp(activity,8))}
        val heading=StudyUi.card(activity,content).apply{background=ModernUi.shape(activity,ModernUi.soft,16)}
        heading.addView(StudyUi.text(activity,item.course.name,17,true))
        heading.addView(StudyUi.text(activity,item.title.ifBlank{"课程签到"},13))
        fun section(title:String,rows:Map<BoundAccount,String?>,tone:Int) {
            if(rows.isEmpty())return
            content.addView(StudyUi.text(activity,"$title · ${rows.size}",13,true,tone))
            for((account,reason) in rows) {
                val row=StudyUi.card(activity,content).apply{background=ModernUi.shape(activity,ModernUi.background,14)}
                row.addView(StudyUi.text(activity,account.label,15,true))
                row.addView(StudyUi.text(activity,reason ?: "仅提交一次",12))
            }
        }
        section("将提交",choices.filterValues{it==null},ModernUi.strongPrimary)
        section("将跳过",choices.filterValues{it!=null},ModernUi.muted)
        content.addView(StudyUi.text(activity,"结果按账号分别显示。结果未知时不会自动重试。",12))
        return ScrollView(activity).apply{addView(content)}
    }
    private fun runAccounts(chosen:List<BoundAccount>,message:String,step:Step,parallel:Boolean=false,onComplete:(()->Unit)?=null,action:(BoundAccount,SignSource,()->Unit)->StepResult) {
        if(busy)return
        val token=++generation;busy=true;state.text=message
        if(parallel && step==Step.SUBMIT)main.post{if(!closed && token==generation && busy)render()} else render()
        task=worker.submit {
            val completed=java.util.concurrent.atomic.AtomicInteger()
            fun runOne(account:BoundAccount,index:Int) {
                if(closed || token!=generation || Thread.currentThread().isInterrupted)return
                val result=try {
                    guard(account,token)
                    fun sessionGuard(){if(closed || Thread.currentThread().isInterrupted || !accountStore.current(account))throw java.util.concurrent.CancellationException()}
                    val api=preparedSources[account.id] ?: (sourceFactory?.invoke(account,::sessionGuard) ?: SigningApi(account.cookies.toList(),::sessionGuard,allowCode=true,allowQr=true,allowGesture=true))
                    activeSources.add(api)
                    try {
                        guard(account,token)
                        DiagnosticLog.operation("BatchSignAccount","account=${DiagnosticLog.reference(account.id)} ${DiagnosticLog.activity(item)} step=${index+1}/${chosen.size} parallel=$parallel"){action(account,api){guard(account,token)}}
                    } finally {activeSources.remove(api);if(preparedSources[account.id]!==api)api.cancel()}
                } catch(e:java.util.concurrent.CancellationException){return}
                catch(e:Exception){DiagnosticLog.error("BatchSignAccount",e);StepResult(message=if(e is ApiProblem || e is LoginRequired)e.message.orEmpty() else "此账号的操作未能确认，请刷新结果。")}
                main.post {
                    if(!closed && token==generation) {
                        val count=completed.incrementAndGet()
                        val id=account.id
                        result.plan?.let{plans[id]=it}
                        result.outcome?.let{outcome->
                            if(step==Step.SUBMIT){submitResults[id]=outcome;queryResults.remove(id)}
                            if(step==Step.QUERY) {
                                queryResults[id]=outcome
                                if(id !in confirmed && submitResults[id]?.successful!=true && ledger.recoverUnsubmittedQr(id,item.copy(otherId=if(plans[id]?.mode==SignMode.QR)2 else item.otherId),outcome))submitResults.remove(id)
                            }
                            if(outcome.successful)confirmed[id]=outcome
                        }
                        if(result.message!=null)notes[id]=result.message else notes.remove(id)
                        state.text="已处理 $count / ${chosen.size}"
                        if(!parallel)render()
                    }
                }
            }
            var pool:java.util.concurrent.ExecutorService?=null
            val pending=mutableListOf<Future<*>>()
            try {
                if(parallel && chosen.size>1) {
                    pool=Executors.newFixedThreadPool(minOf(10,chosen.size))
                    chosen.forEachIndexed{index,account->pending+=pool!!.submit{runOne(account,index)}}
                    pending.forEach{it.get()}
                } else chosen.forEachIndexed{index,account->runOne(account,index)}
            } catch(e:InterruptedException){Thread.currentThread().interrupt()}
            catch(e:Exception){DiagnosticLog.error("BatchSign",e)}
            finally {
                pending.forEach{if(!it.isDone)it.cancel(true)};pool?.shutdownNow()
                main.post{if(!closed && token==generation){busy=false;task=null;state.text="处理结束，请查看每个账号的结果。";render();onComplete?.invoke()}}
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun prepareAndScan() {
        if(busy || closed)return
        val ready=chosen().filter{skipReason(it)==null && plans[it.id]?.mode==SignMode.QR}
        if(ready.isEmpty()){state.text="请先选择账号并核对二维码签到要求。";return}
        if(ready.any{plans[it.id]?.requiresLocation==true} && runCatching{fix?.requireCurrent() ?: throw ApiProblem("未定位")}.isFailure){state.text="请先更新本次手机定位，再扫码。";return}
        autoQrLoop=true;qr=null
        runAccounts(ready,"正在提前准备所选账号和网络连接…",Step.CHECK,parallel=true,onComplete={
            val prepared=ready.filter{preparedTickets[it.id]?.current()==true && accountStore.current(it)}
            if(prepared.isNotEmpty()) {
                scanningAccounts=prepared.map{it.id}
                openQrScanner(prepared)
            } else state.text="所选账号尚未准备成功，请查看各账号提示后重试。"
        }) {account,api,check->
            check()
            val ticket=preparedTickets[account.id]?.takeIf{it.current()} ?: api.prepareQr(item)
            preparedSources[account.id]=api;preparedTickets[account.id]=ticket
            StepResult(plan=ticket.plan)
        }
    }
    private fun submitScannedQr(material:SignQr) {
        val candidates=chosen().filter{it.id in scanningAccounts && skipReason(it)==null}
        if(candidates.isEmpty() || busy)return
        val ready=candidates.filter{preparedTickets[it.id]?.current()==true}
        candidates.filterNot{it in ready}.forEach{notes[it.id]="准备已过期，请重新准备后扫描新码。"}
        if(ready.isEmpty()){state.text="账号准备已过期，正在重新准备并扫描新码。";prepareAndScan();return}
        val position=fix
        lastQrEnc=material.enc
        runAccounts(ready,"已识别，所选账号正在同时提交…",Step.SUBMIT,parallel=true,onComplete={
            qr=null
            if(submitResults.values.any{it.retryAllowed}) {rejectedQrEnc=material.enc;main.post{continueQrScan()}}
            if(submitResults.values.any{it.category==SignResultCategory.UNKNOWN})main.postDelayed({if(!closed && foreground && !busy)refreshResults(automatic=true)},1_500)
            if(chosen().all{it.id in confirmed})autoQrLoop=false
        }) {account,api,check->
            val outcome=SignSubmission(ledger).submit(account.id,item,api,
                SignInputs(qr=material,fix=position,expectedMode=SignMode.QR,preparedQr=preparedTickets[account.id]),check)
            StepResult(outcome=outcome)
        }
    }
    @Suppress("DEPRECATION")
    private fun openQrScanner(ready:List<BoundAccount>) {
        if(closed || !autoQrLoop || ready.isEmpty())return
        scanningAccounts=ready.map{it.id}
        startActivityForResult(QrScanActivity.materialIntent(this,item.id,ready.first().id)
            .putExtra("autoStartCamera",true).putExtra(QrScanActivity.REJECTED_ENC,rejectedQrEnc),QR)
    }
    private fun continueQrScan() {
        if(closed || !foreground || busy || !autoQrLoop)return
        val ready=chosen().filter{skipReason(it)==null && plans[it.id]?.mode==SignMode.QR}
        if(ready.isEmpty())return
        if(ready.all{preparedTickets[it.id]?.current()==true})openQrScanner(ready) else prepareAndScan()
    }
    private fun locate() {
        if(busy)return
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION);return}
        state.text="正在获取本次定位…"
        location.request{value->if(!closed){fix=value;state.text=if(value==null)"未取得有效定位，请重试。" else "定位已更新，请核对后提交。";render()}}
    }
    @Deprecated("Platform picker") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request==QR && result!=RESULT_OK){autoQrLoop=false;qr=null;state.text="已停止扫码自动提交。"}
        if(request==QR && result==RESULT_OK && !busy) {
            val scanned=SignQr.fromResult(data)?.takeIf{it.activityId==item.id}
            if(scanned==null){state.text="二维码与此活动不匹配，请重新扫描。";render()}else submitScannedQr(scanned)
            return
        }
        if(request!=PHOTO)return
        val owner=picking;picking=null
        val uri=data?.data
        if(result!=RESULT_OK || owner==null || uri==null || busy)return
        val token=++generation;busy=true;render();state.text="正在准备照片…"
        task=worker.submit {
            try {
                val file=SignPhoto.prepare(this,uri)
                val preview=file.inputStream().use{android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply{inSampleSize=4})}
                if(preview==null){file.delete();throw ApiProblem("照片无法预览，请重选。")}
                main.post{if(closed || token!=generation){file.delete();preview.recycle()} else {previews.put(owner,preview)?.recycle();photos.put(owner,file)?.delete();busy=false;task=null;state.text="照片已准备，请核对对应账号和预览后提交。";render()}}
            }catch(e:Exception){DiagnosticLog.error("BatchPhoto",e);main.post{if(!closed && token==generation){busy=false;task=null;state.text="照片准备失败，请重选。";render()}}}
        }
    }
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(request,permissions,results)
        if(request==LOCATION && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()
    }
    private fun cancel(){autoQrLoop=false;generation++;preparedSources.values.forEach{it.cancel()};preparedSources.clear();preparedTickets.clear();activeSources.toList().forEach{it.cancel()};task?.cancel(true);task=null;busy=false}
    override fun onResume(){super.onResume();foreground=true}
    override fun onPause(){foreground=false;super.onPause()}
    override fun onDestroy(){closed=true;cancel();dialog?.dismiss();if(::location.isInitialized)location.cancel();worker.shutdownNow();photos.values.forEach{it.delete()};photos.clear();previews.values.forEach{it.recycle()};previews.clear();super.onDestroy()}
    companion object {
        private const val PHOTO=5801;private const val LOCATION=5802;private const val QR=5803
        fun intent(activity:Activity,item:SignActivity)=Intent(activity,MultiAccountSignActivity::class.java)
            .putExtra("courseKey",item.course.key).putExtra("activeId",item.id).putExtra("title",item.title).putExtra("otherId",item.otherId ?: -1)
    }
}
