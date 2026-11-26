package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import java.io.File

class SignActionActivity:StudyActivity() {
    override val requestedBoundAccountId:String? get()=intent.getStringExtra("boundAccountId")
    internal var sourceFactory:((()->Unit)->SignSource)?=null
    // Instrumented tests supply an isolated ledger and trace sink before onCreate.
    internal var ledgerRepository:SignLedger?=null
    internal var requirementTrace:(String)->Unit={AlertTrace.add(this,it)}
    private val ledger get()=ledgerRepository ?: app.signing.ledger
    private lateinit var sign:SignActivity
    private lateinit var source:SignSource
    private lateinit var deviceLocation:DeviceLocation
    private var qr:SignQr?=null
    private var preparedQr:PreparedQr?=null
    private var autoQrLoop=false
    private var rejectedQrEnc:String?=null
    private var lastQrEnc:String?=null
    private var foreground=false
    private var gesture=""
    private var fix:LocationFix?=null
    private var manualPoint:ManualLocation?=null
    private var photo:File?=null
    private var photoPreview:Bitmap?=null
    private var submittingPhoto=false
    private var lastOutcome:SignOutcome?=null
    private var resultCheck:SignOutcome?=null
    private var plan:SignPlan?=null
    private var overview:SignOverview?=null
    private var dialog:AlertDialog?=null
    private val monitoredPhoto get()=intent.getBooleanExtra("monitoredPhoto",false)
    private val historyOnly get()=intent.getBooleanExtra("historyOnly",false)
    private val ended get()=historyOnly || overview?.status==2 || plan?.issueCode=="ACTIVITY_ENDED"
    private fun submissionLocked()=lastOutcome?.locksSignSubmission==true || ledger.attempted(sessionOwner,sign)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!valid()){finish();return}
        val course=sessionCourses.firstOrNull{it.key==intent.getStringExtra("courseKey")} ?: run{finish();return}
        val id=intent.getStringExtra("activeId").orEmpty();if(!SignProtocol.validId(id)){finish();return}
        sign=SignActivity(id,intent.getStringExtra("title").orEmpty(),course,otherId=intent.getIntExtra("otherId",-1).takeIf { it>=0 })
        titleView.text=when { historyOnly->"签到结果"; sign.otherId==4->"位置签到"; else->"签到详情" }
        source=sourceFactory?.invoke(::guardCourse) ?: SigningApi(sessionCookies,::guardCourse,allowCode=true,allowQr=true,allowGesture=true)
        deviceLocation=DeviceLocation(this)
        qr=null // Never reuse material carried through course discovery or page recreation.
        render()
        if(historyOnly)checkAttendance() else checkRequirements()
    }
    private fun guardCourse() {
        guard()
        if(boundSession==null && monitoredPhoto && app.selectedCourses().none { it.key==sign.course.key })
            throw ApiProblem("该课程已取消提醒，请刷新签到列表。")
    }
    private fun checkRequirements() {
        if(working)return
        work("正在核对签到要求…",{source.plan(sign)}){result->
            plan=result
            overview=result.overview
            if(result.mode==SignMode.PHOTO)titleView.text="拍照签到"
            requirementTrace("核对签到详情 · ${result.message}")
            render()
            if(result.issueCode in setOf("ACTIVITY_NOT_ACTIVE","ACTIVITY_ENDED") || (result.mode==SignMode.QR && submissionLocked()))checkAttendance()
            else if(result.mode==SignMode.QR && intent.getBooleanExtra("autoScan",false)){
                intent.removeExtra("autoScan");prepareAndScan()
            }
        }
    }
    private fun render() {
        body.removeAllViews()
        val info=StudyUi.card(this,body)
        val heading=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        heading.addView(StudyUi.text(this,overview?.type ?: "课程签到",13,true,ModernUi.strongPrimary),
            LinearLayout.LayoutParams(0,-2,1f))
        val badge=if(ended) "已结束" else when(overview?.status){1->"进行中";else->"状态待核实"}
        heading.addView(StudyUi.text(this,badge,12,true,if(ended)ModernUi.muted else ModernUi.primary).apply{
            background=ModernUi.shape(this@SignActionActivity,if(ended)ModernUi.background else ModernUi.soft,8)
            setPadding(ModernUi.dp(this@SignActionActivity,8),ModernUi.dp(this@SignActionActivity,4),
                ModernUi.dp(this@SignActionActivity,8),ModernUi.dp(this@SignActionActivity,4))
        })
        info.addView(heading)
        info.addView(StudyUi.text(this,activityName(),22,true))
        info.addView(StudyUi.text(this,sign.course.name,13))
        val metadata=overview
        val start=metadata?.startsAt
        val end=metadata?.endsAt
        field(info,"开始时间",start?.let(SignOverview::format) ?: "未提供")
        if(start==null)metadata?.publishedAt?.let{field(info,"发布时间",SignOverview.format(it))}
        field(info,"截止时间",end?.let(SignOverview::format) ?: "未提供")
        if(end==null && start==null)intent.getStringExtra("timeline")?.takeIf{it.isNotBlank()}
            ?.let{field(info,"活动记录",it)}
        val count=metadata?.attendedCount?.takeUnless{it==0 && lastOutcome?.successful==true}
        field(info,"已签人数",count?.let{"$it 人"} ?: "暂未提供")

        val resultCard=StudyUi.card(this,body)
        val locked=submissionLocked()
        val result=lastOutcome ?: if(locked)null else resultCheck
        val label=result?.category?.label ?: if(locked)SignResultCategory.UNKNOWN.label else if(ended)"尚未查询" else "尚未提交"
        resultCard.addView(StudyUi.text(this,"操作账号：$sessionLabel",13,true))
        resultCard.addView(StudyUi.text(this,"签到结果 · $label",16,true,
            if(result?.successful==true)ModernUi.strongPrimary else ModernUi.ink))
        if(result!=null) {
            if(result.photoState!=PhotoTransferState.NOT_SENT)resultCard.addView(StudyUi.text(this,result.photoState.label,13))
            resultCard.addView(StudyUi.text(this,result.message,13))
        } else if(locked) {
            resultCard.addView(StudyUi.text(this,"已有提交记录，请刷新结果，不要重复提交。",13))
        }
        resultCheck?.takeIf{it!==result && !it.successful}?.let{
            val note=if(result?.successful==true)"\n保留此前已确认的签到结果。" else ""
            resultCard.addView(StudyUi.text(this,"结果查询：${it.message}$note",13))
        }
        resultCard.addView(StudyUi.button(this,"刷新学习通签到结果"){checkAttendance(refreshOverview=true)}.apply{
            ModernUi.button(this,textOnly=true);isEnabled=!working
        })
        if(ended || locked) {
            addActivityTools(includeDetection=!ended)
            return
        }
        val details=StudyUi.card(this,body)
        details.addView(StudyUi.text(this,"签到要求",16,true))
        if(plan?.repairNeeded==true)details.addView(StudyUi.text(this,if(plan?.issueCode=="LOCATION_DISABLED")"不可用" else "待修复",13,true,ModernUi.danger))
        details.addView(StudyUi.text(this,plan?.message ?: "正在读取签到要求…",13))
        plan?.requirements?.lines?.filterNot{it.startsWith("截止：")}?.forEach{
            details.addView(StudyUi.text(this,it,13))
        }
        when(plan?.mode) {
            SignMode.NORMAL -> details.addView(StudyUi.button(this,"确认并提交签到",true){confirm()})
            SignMode.CODE -> {
                val input=EditText(this).apply{hint="老师公布的签到码";inputType=android.text.InputType.TYPE_CLASS_NUMBER;ModernUi.input(this)};details.addView(input)
                details.addView(StudyUi.button(this,"确认签到码并提交",true){confirm(input.text.toString())})
            }
            SignMode.QR -> {
                details.addView(StudyUi.text(this,plan?.qrRefresh?.description().orEmpty(),13,true,ModernUi.strongPrimary))
                details.addView(StudyUi.text(this,"先准备账号和签到要求；识别后立即提交，无需再次确认。",13))
                details.addView(StudyUi.button(this,"扫码并立即提交",true){prepareAndScan()}.apply{isEnabled=!working})
                if(plan?.requiresLocation==true) {
                    details.addView(StudyUi.text(this,fix?.let{"本次位置：${it.latitude}, ${it.longitude}"} ?: "本次二维码还要求手机位置。",13))
                    details.addView(StudyUi.button(this,"更新本次手机定位"){locate()})
                }
                details.addView(StudyUi.text(this,"在 ClassBell 内在线提交，结果在本页显示。",13))

            }
            SignMode.GESTURE -> {
                details.addView(gestureInput(this,gesture,!working){gesture=it})
                details.addView(StudyUi.button(this,"确认手势并提交",true){confirm()})
            }
            SignMode.PHOTO -> renderPhoto()
            SignMode.LOCATION -> {
                val card=StudyUi.card(this,body);card.background=ModernUi.shape(this,ModernUi.soft,22)
                card.addView(StudyUi.text(this,if(manualPoint==null)"手机当前位置" else "本次使用手动位置",19,true));card.addView(StudyUi.text(this,manualPoint?.description() ?: fix?.let{"纬度 ${it.latitude}\n经度 ${it.longitude}\n精度约 ${it.accuracy.toInt()} 米"} ?: "可获取手机定位，或手动选点。"))
                card.addView(StudyUi.button(this,"获取 / 更新当前位置",true){locate()})
                card.addView(StudyUi.button(this,"手动选点 / 填写经纬度"){
                    val intent=Intent(this,LocationPickerActivity::class.java).putExtra("return",true)
                    (manualPoint ?: fix?.let{ManualLocation(it.latitude,it.longitude)})?.putInto(intent)
                    @Suppress("DEPRECATION") startActivityForResult(intent,PICK_LOCATION)
                })
                app.locationPresets.get(sessionOwner)?.let{value->
                    card.addView(StudyUi.text(this,"账号预备位置：\n${value.description()}",12))
                    card.addView(StudyUi.button(this,"使用账号预备位置"){deviceLocation.cancel();fix=null;manualPoint=value;render()})
                }
                (manualPoint ?: fix?.let{ManualLocation(it.latitude,it.longitude)})?.let{value->card.addView(StudyUi.button(this,"在地图查看 / 调整"){
                    @Suppress("DEPRECATION") startActivityForResult(value.putInto(Intent(this,LocationPickerActivity::class.java).putExtra("return",true)),PICK_LOCATION)
                })}
                card.addView(StudyUi.button(this,if(manualPoint==null)"确认当前位置并签到" else "确认手动位置并签到",true){confirm()})
                card.addView(StudyUi.text(this,"手动位置只在你确认后提交，不修改手机 GPS。当前尚无进行中的真实活动完成提交验证，结果以学习通为准。",12))
            }
            null, SignMode.OFFICIAL -> details.addView(StudyUi.button(this,"重新核对签到要求"){checkRequirements()})
        }
        addActivityTools(includeDetection=true)
    }
    private fun activityName()=overview?.name?.takeIf{it.isNotBlank()} ?: sign.title.ifBlank{"课程签到"}
    private fun field(parent:LinearLayout,title:String,value:String) {
        val row=LinearLayout(this)
        row.addView(StudyUi.text(this,title,12,true),LinearLayout.LayoutParams(ModernUi.dp(this,76),-2))
        row.addView(StudyUi.text(this,value,13),LinearLayout.LayoutParams(0,-2,1f))
        parent.addView(row)
    }
    private fun addActivityTools(includeDetection:Boolean) {
        val tools=StudyUi.column(this)
        body.addView(tools)
        if(includeDetection) {
            val ignored=ledger.ignored(sessionOwner,sign.key)
            tools.addView(StudyUi.button(this,if(ignored)"恢复检测这个签到" else "不检测这个签到"){
                if(valid() && !working) {
                    app.setSignDetectionIgnored(sessionOwner,sign,!ignored)
                    render()
                }
            }.apply{ModernUi.button(this,textOnly=true);isEnabled=!working})
        }
        tools.addView(StudyUi.button(this,"分享活动提醒"){
            if(!valid())return@button
            val message=buildString {
                appendLine("学习通活动提醒")
                appendLine(sign.course.name)
                appendLine(activityName())
                overview?.startsAt?.let{appendLine("开始：${SignOverview.format(it)}")}
                overview?.endsAt?.let{appendLine("截止：${SignOverview.format(it)}")}
                appendLine("请使用自己的账号查看课程活动。")
                append("https://i.chaoxing.com/")
            }
            val share=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message)
            startActivity(Intent.createChooser(share,"分享活动提醒"))
        }.apply{ModernUi.button(this,textOnly=true)})
    }
    private fun checkAttendance(refreshOverview:Boolean=historyOnly) {
        if(!valid() || working)return
        work("正在查询签到结果…",{
            val result=try{source.attendance(sign)} catch(e:Exception){
                if(e is java.util.concurrent.CancellationException)throw e
                DiagnosticLog.error("SignAttendance",e)
                val message=if(e is ApiProblem || e is LoginRequired)e.message.orEmpty() else ""
                SignOutcome(false,message.ifBlank{"暂时无法查询签到结果，请稍后刷新。"},SignResultCategory.UNKNOWN)
            }
            val metadata=if(refreshOverview)try{source.overview(sign)} catch(e:Exception){
                if(e is java.util.concurrent.CancellationException)throw e
                DiagnosticLog.error("SignOverview",e)
                null
            }else null
            result to metadata
        }) { (result,metadata) ->
            if(metadata!=null)overview=metadata
            resultCheck=result
            if(result.successful) {
                autoQrLoop=false
                lastOutcome=result.copy(photoState=lastOutcome?.photoState ?: PhotoTransferState.NOT_SENT)
                ledger.confirmSuccess(sessionOwner,sign)
                ledger.record(sessionOwner,sign,"结果查询 · ${result.message}")
                qr=null;gesture="";clearPhoto()
            }
            if(lastOutcome?.successful!=true && ledger.recoverUnsubmittedQr(sessionOwner,sign.copy(otherId=if(plan?.mode==SignMode.QR)2 else sign.otherId),result)) {
                lastOutcome=null;qr=null;preparedQr=null
                state.text="学习通确认未签到，可以重新扫描当前码立即提交。"
            }
            if(!ended && plan?.mode==SignMode.PHOTO && lastOutcome?.successful!=true && submissionLocked() &&
                ledger.recoverUnsubmittedPhoto(sessionOwner,sign.copy(otherId=0),result)) {
                lastOutcome=null;clearPhoto()
                state.text="学习通确认未签到，请重新选择符合要求的照片后再提交。"
            }
            render()
            if(autoQrLoop && foreground && lastQrEnc!=null && !submissionLocked() && result.category==SignResultCategory.NOT_SUBMITTED) {
                rejectedQrEnc=lastQrEnc;main.post{continueQrScan()}
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun prepareAndScan() {
        if(!valid() || working || ended || submissionLocked())return
        if(plan?.requiresLocation==true && runCatching{fix?.requireCurrent() ?: throw ApiProblem("未定位")}.isFailure){state.text="请先更新本次手机定位，再扫码。";return}
        autoQrLoop=true;qr=null
        work("正在准备账号和网络连接…",{source.prepareQr(sign)}) {prepared->
            preparedQr=prepared;plan=prepared.plan;overview=prepared.plan.overview ?: overview
            render()
            openQrScanner()
        }
    }
    private fun submitScannedQr(material:SignQr) {
        if(!valid() || working || ended || submissionLocked())return
        val prepared=preparedQr
        if(prepared==null || !prepared.current()) {preparedQr=null;state.text="扫码准备已过期，正在重新准备。";prepareAndScan();return}
        val owner=sessionOwner
        lastQrEnc=material.enc
        work("已识别，正在立即提交…",{
            SignSubmission(ledger).submit(owner,sign,source,SignInputs(qr=material,fix=fix,expectedMode=SignMode.QR,preparedQr=prepared),::guardCourse)
        }) {outcome->
            lastOutcome=outcome;resultCheck=null;qr=null;state.text=outcome.message;render()
            if(outcome.successful)autoQrLoop=false
            else if(outcome.retryAllowed) {rejectedQrEnc=material.enc;main.post{continueQrScan()}}
            else if(outcome.category==SignResultCategory.UNKNOWN)main.postDelayed({if(valid() && !working)checkAttendance()},1_500)
        }
    }
    @Suppress("DEPRECATION")
    private fun openQrScanner() {
        if(!valid() || !autoQrLoop || ended)return
        startActivityForResult(QrScanActivity.materialIntent(this,sign.id,requestedBoundAccountId)
            .putExtra("autoStartCamera",true).putExtra(QrScanActivity.REJECTED_ENC,rejectedQrEnc),PICK_QR)
    }
    private fun continueQrScan() {
        if(!valid() || !foreground || working || !autoQrLoop || ended || submissionLocked())return
        if(preparedQr?.current()==true)openQrScanner() else prepareAndScan()
    }
    private fun renderPhoto() {
        val card=StudyUi.card(this,body)
        card.addView(StudyUi.text(this,"本次签到照片",19,true))
        card.addView(StudyUi.text(this,SignPhoto.PRIVACY_NOTICE,12))
        val preview=photoPreview
        if(preview==null)card.addView(StudyUi.text(this,"从相册选择一张符合老师要求的照片。"))
        else {
            val image=ImageView(this).apply {
                scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="待提交的签到照片"
                setImageBitmap(preview)
            }
            card.addView(image,LinearLayout.LayoutParams(-1,ModernUi.dp(this,240)))
            card.addView(StudyUi.text(this,"已在本机处理并预览，尚未上传。确认后发送到学习通。",13))
        }
        card.addView(StudyUi.button(this,if(preview==null)"从相册选择本次照片" else "更换照片",preview==null){
            if(!valid() || working || submittingPhoto)return@button
            try {
                SignPhoto.openPicker(this,PHOTO)
            } catch (diagnosticError: android.content.ActivityNotFoundException) { DiagnosticLog.error("SignActionActivity", diagnosticError);
                state.text="手机没有可用的图片选择器。"
            }
        }.apply{isEnabled=!submittingPhoto})
        if(preview!=null) {
            card.addView(StudyUi.button(this,"移除本次照片"){
                if(!working && !submittingPhoto){clearPhoto();state.text="本次照片已移除，未上传。";render()}
            }.apply{isEnabled=!submittingPhoto})
            card.addView(StudyUi.button(this,"上传并签到",true){confirm()}.apply{isEnabled=!submittingPhoto})
        }
        if(app.boundAccounts.list().any{account->account.courses.any{it.key==sign.course.key}}) {
            card.addView(StudyUi.button(this,"多账号分别选图签到"){
                if(valid() && !working && !submittingPhoto)startActivity(MultiAccountSignActivity.intent(this,sign))
            }.apply{isEnabled=!working && !submittingPhoto})
        }
    }
    private fun clearPhoto() {
        photo?.delete();photo=null
        photoPreview?.recycle();photoPreview=null
    }
    private fun locate() {
        manualPoint=null
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION);return}
        state.text="正在获取位置，最多等待 30 秒…"
        deviceLocation.request {value->if(!isDestroyed){fix=value;state.text=if(value==null)"未取得足够精确的新定位，请检查系统定位和权限后重试。" else "定位已更新，请核对后提交。";render()}}
    }
    private fun confirm(code:String="") {
        if(!valid() || working || ended || dialog?.isShowing==true)return
        if(submissionLocked()){render();return}
        val mode=plan?.mode ?: return
        val selectedPhoto=photo
        if(mode==SignMode.PHOTO && (submittingPhoto || selectedPhoto?.isFile!=true))return
        val selectedQr=qr;val selectedGesture=gesture
        if(mode==SignMode.QR && diagnosticRunCatching{selectedQr?.requireFor(sign) ?: throw ApiProblem("请扫描当前二维码")}.isFailure){state.text="请扫描当前活动的二维码。";return}
        if(mode==SignMode.GESTURE && !GesturePath.valid(selectedGesture)){state.text="请连接至少两个不同的点，或输入正确的数字路径。";return}
        if(mode==SignMode.QR && plan?.requiresLocation==true && diagnosticRunCatching{fix?.requireCurrent() ?: throw ApiProblem("未定位")}.isFailure){state.text="请更新本次手机定位。";return}
        val selectedPoint=manualPoint
        val selectedFix=fix
        if(mode==SignMode.LOCATION && diagnosticRunCatching{if(selectedPoint!=null)selectedPoint.requireValid() else selectedFix?.requireCurrent() ?: throw ApiProblem("")}.isFailure){state.text="请先获取当前位置，或选择有效的手动位置。";return}
        if(mode==SignMode.CODE && !Regex("[0-9]{4,16}").matches(code)){state.text="请填写老师公布的签到码。";return}
        val position=if(mode==SignMode.LOCATION)selectedPoint?.let{"\n\n手动位置\n${it.description()}"} ?: selectedFix?.let{"\n\n手机位置\n纬度 ${it.latitude}\n经度 ${it.longitude}"}.orEmpty() else ""
        dialog=ModernUi.dialog(this).setTitle(if(mode==SignMode.PHOTO)"确认上传并签到" else "提交本次签到")
            .setMessage("${sign.course.name}\n${sign.title}$position\n\n操作账号：$sessionLabel。${if(mode==SignMode.PHOTO)"将预览中的照片上传到学习通，用于本次普通拍照签到。\n\n" else ""}仅提交这项签到，结果未知时不会自动重试。")
            .setNegativeButton("取消",null).setPositiveButton("提交一次"){_,_->
                if(!valid() || working || submittingPhoto || ended)return@setPositiveButton
                if(submissionLocked()){render();return@setPositiveButton}
                val owner=sessionOwner
                if(mode==SignMode.PHOTO){submittingPhoto=true;render()}
                work("正在提交，请勿重复操作…",{
                    SignSubmission(ledger).submit(owner,sign,source,
                        SignInputs(code=code,photo=selectedPhoto,fix=selectedFix,point=selectedPoint,expectedMode=mode,qr=selectedQr,gesture=selectedGesture),::guardCourse)
                }){result->
                    lastOutcome=result
                    resultCheck=null
                    state.text=result.message
                    if(mode==SignMode.QR && result.retryAllowed)qr=null
                    if(result.successful){qr=null;gesture=""}
                    if(mode==SignMode.PHOTO){submittingPhoto=false;clearPhoto()}
                    render()
                }
            }.create().apply{setOnDismissListener{dialog=null};show()}
    }
    @Deprecated("Platform picker") override fun onActivityResult(code:Int,result:Int,data:Intent?) {
        super.onActivityResult(code,result,data)
        if(code==PICK_QR && result!=RESULT_OK){autoQrLoop=false;qr=null;state.text="已停止扫码自动提交。"}
        if(code==PICK_QR && result==RESULT_OK && valid() && !working) {
            val scanned=SignQr.fromResult(data)?.takeIf{it.activityId==sign.id}
            if(scanned==null){state.text="二维码与当前活动不匹配，请重扫。";render()} else submitScannedQr(scanned)
        }
        if(code==PICK_LOCATION && result==RESULT_OK && data!=null && valid()) {
            diagnosticRunCatching{ManualLocation.fromIntent(data)}
                .onSuccess{deviceLocation.cancel();fix=null;manualPoint=it;render()}.onFailure{state.text="位置选择无效，请重选。"}
        }
        if(code==PHOTO && result==RESULT_OK && valid() && plan?.mode==SignMode.PHOTO && !working && !submittingPhoto) {
            val uri=data?.data ?: return
            work("正在准备照片…",{SignPhoto.prepare(this,uri)}){file->
                val preview=diagnosticRunCatching {
                    file.inputStream().use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=2}) }
                }.getOrNull()
                if(preview==null) {
                    file.delete();state.text="照片无法预览，请重新选择。"
                } else {
                    clearPhoto();lastOutcome=null;photo=file;photoPreview=preview;render()
                }
            }
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);DiagnosticLog.event("Permissions", "request=$code granted=${results.count{it==android.content.pm.PackageManager.PERMISSION_GRANTED}} total=${results.size}");if(code==LOCATION){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate() else state.text="需要允许精确定位，也可使用官方页面处理。"}}
    override fun onResume(){super.onResume();foreground=true}
    override fun onPause(){foreground=false;super.onPause()}
    override fun onStop(){if(::deviceLocation.isInitialized)deviceLocation.cancel();super.onStop()}
    override fun onDestroy(){dialog?.dismiss();clearPhoto();if(::source.isInitialized)source.cancel();if(::deviceLocation.isInitialized)deviceLocation.cancel();super.onDestroy()}
    companion object {
        const val PHOTO=5201;const val LOCATION=5202;const val PICK_LOCATION=5203;const val PICK_QR=5204
        fun intent(a:Activity,sign:SignActivity,monitoredPhoto:Boolean=false,boundAccountId:String?=null,
                   historyOnly:Boolean=false,timeline:String="")=Intent(a,SignActionActivity::class.java)
            .putExtra("courseKey",sign.course.key).putExtra("activeId",sign.id).putExtra("title",sign.title)
            .putExtra("otherId",sign.otherId ?: -1).putExtra("monitoredPhoto",monitoredPhoto).putExtra("boundAccountId",boundAccountId)
            .putExtra("historyOnly",historyOnly)
            .putExtra("timeline",timeline)
    }
}

internal val SignOutcome.locksSignSubmission: Boolean
    get()=successful || category==SignResultCategory.UNKNOWN || category==SignResultCategory.ALREADY_ATTEMPTED
