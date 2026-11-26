package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Native, user-triggered export. Reading the page never downloads its images or attachments. */
class HomeworkExportActivity:StudyActivity() {
    internal var detailLoader:((HomeworkItem)->HomeworkDetailPage)?=null
    internal var imageSaveAction:((List<String>,String,(Int,Int)->Unit)->HomeworkImageSaveResult)?=null
    private var item:HomeworkItem?=null
    private var questions:HomeworkQuestionSet?=null
    private var expectedQuestions:Int?=null
    private var reader:HomeworkDetailSource?=null
    private var saver:HomeworkImageSaver?=null
    private var reading=false
    private var saving=false
    private var limit=30
    private var pendingImages:List<String>?=null
    private var pendingPrefix=""
    private var lastSave:HomeworkImageSaveResult?=null
    private var saveGeneration=0
    private var enterRequired=false
    private var enterApproved=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text="题目文字与图片"
        if(!valid() || intent.getStringExtra("owner")!=sessionOwner){finish();return}
        item=runCatching {
            val raw=intent.getStringExtra("homework") ?: throw IllegalArgumentException()
            require(raw.length<=32_000)
            HomeworkSnapshot.fromJson(JSONObject().put("fetchedAt",0).put("items",JSONArray().put(JSONObject(raw))).toString()).items.single()
        }.getOrNull()
        if(item==null){state.text="作业信息无效，请从作业列表重新进入。";return}
        render();readQuestions()
    }
    private fun render() {
        val work=item ?: return
        body.removeAllViews()
        val card=StudyUi.card(this,body)
        card.addView(StudyUi.text(this,work.title,21,true))
        card.addView(StudyUi.text(this,work.courseName,13))
        val set=questions
        card.addView(StudyUi.text(this,when {
            set == null -> "读取题目后，可复制文字或按需保存题图。"
            set.questions.isEmpty() -> "尚未读取到题目"
            else -> "${set.questions.size} 道题 · ${set.imageUrls.size} 张题图"
        },14,true,ModernUi.strongPrimary))
        card.addView(StudyUi.text(this,"选择、填空、判断、简答均按题整理。图片仅在点击保存后下载到相册，文档附件不处理。",12))
        if(set!=null && (set.skipped>0 || set.unsupportedImages>0))card.addView(StudyUi.text(this,
            "请核对：${set.skipped} 处题目结构未识别，${set.unsupportedImages} 处图片来源暂不支持。",12,true,ModernUi.danger))
        if(enterRequired) {
            card.addView(StudyUi.text(this,"进入可能创建本次作业的答题记录，题目读取不会填写答案或提交。",12))
            card.addView(StudyUi.button(this,"进入这份作业并读取题目",true){readQuestions(true)}.apply{isEnabled=!reading && !saving && valid()})
        }
        card.addView(StudyUi.button(this,if(reading)"正在读取题目…" else "重新读取题目"){readQuestions()}.apply{isEnabled=!reading && !saving})
        val complete=set!=null && (expectedQuestions==null || set.questions.size==expectedQuestions)
        if(set!=null && !complete)card.addView(StudyUi.text(this,"题目尚未读全（${set.questions.size} / $expectedQuestions），请重新读取。",12,true,ModernUi.danger))
        card.addView(StudyUi.button(this,if(set!=null && set.skipped>0)"复制已识别的全部文字" else "复制全部题目文字",true){copyText()}.apply{isEnabled=valid() && !reading && complete && set?.questions?.isNotEmpty()==true})
        card.addView(StudyUi.button(this,"保存全部题图到相册（${set?.imageUrls?.size ?: 0} 张）"){
            saveImages(set?.imageUrls.orEmpty(),"ClassBell-全部题图")
        }.apply{isEnabled=valid() && !reading && !saving && set?.imageUrls?.isNotEmpty()==true})
        if(reading || saving)card.addView(StudyUi.button(this,if(saving)"停止保存剩余图片" else "停止读取"){
            val wasSaving=saving
            saveGeneration++;reader?.cancel();saver?.cancel();cancelWork();reading=false;saving=false
            state.text=if(wasSaving)"已停止，之前成功保存的图片仍在相册。" else "已停止读取，可以重新读取题目。";render()
        })
        lastSave?.let { result->
            card.addView(StudyUi.text(this,"本次保存 ${result.saved} / ${result.total} 张；失败 ${result.failed} 张。",13,true,
                if(result.failed==0)ModernUi.strongPrimary else ModernUi.danger))
            result.failures.take(5).forEach{card.addView(StudyUi.text(this,"图片 ${it.number}：${it.message}",12))}
            if(result.failures.size>5)card.addView(StudyUi.text(this,"其余 ${result.failures.size-5} 张未保存，可按题重试。",12))
        }
        set?.questions?.take(limit)?.forEach { question->
            val row=StudyUi.card(this,body)
            row.addView(StudyUi.text(this,"第 ${question.number} 题 · ${question.kind}",16,true,ModernUi.strongPrimary))
            val text=set.exportText("","",question).substringAfter('\n')
            row.addView(StudyUi.text(this,text.take(12_000)+(if(text.length>12_000)"\n显示已缩略，复制会保留完整文字。" else ""),15).apply{setTextIsSelectable(true)})
            val actions=LinearLayout(this)
            actions.addView(StudyUi.button(this,"复制本题文字"){copyText(question)}.apply{isEnabled=valid()},LinearLayout.LayoutParams(0,-2,1f))
            if(question.images.isNotEmpty())actions.addView(StudyUi.button(this,"保存本题图片（${question.images.size}）"){
                saveImages(question.images,"ClassBell-第${question.number}题")
            }.apply{isEnabled=valid() && !reading && !saving},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ModernUi.dp(this@HomeworkExportActivity,8)})
            row.addView(actions)
        }
        if(set!=null && set.questions.size>limit)body.addView(StudyUi.button(this,"继续显示题目（还有 ${set.questions.size-limit} 题）"){
            limit+=30;render()
        })
    }
    private fun readQuestions(enter:Boolean=false) {
        val work=item ?: return
        if(reading || saving || working || !valid())return
        if(enter)enterApproved=true
        enterRequired=false
        reader?.cancel()
        val currentReader=HomeworkDetailSource(sessionCookies,::guard)
        reader=currentReader
        reading=true;render()
        this.work("正在读取作业题目…",{
            try {
                val page=detailLoader?.invoke(work) ?: currentReader.load(work,enterApproved){done,total->
                    main.post{if(valid() && reading && reader===currentReader)state.text="正在读取作业题目 $done / $total…"}
                }
                guard();Result.success(Triple(HomeworkQuestionParser.parse(page.html,page.url),page.url,page.expectedQuestions))
            }catch(e:java.util.concurrent.CancellationException){throw e}
            catch(e:Exception){Result.failure<Triple<HomeworkQuestionSet,String,Int?>>(e)}
        }) { result->
            reading=false
            result.fold({(set,pageUrl,total)->
                saver?.close();saver=HomeworkImageSaver(sessionCookies,::guard,sourcePage=pageUrl)
                questions=set;expectedQuestions=total;limit=30;lastSave=null
                state.text=if(set.questions.isEmpty())"本页未包含可识别题目，可能仍是作业预览页；没有复制整页导航或附件内容。" else if(total!=null && set.questions.size!=total)"题目尚未读全，请重新读取；不能将部分题目作为全部复制。" else "题目已读取，图片尚未下载。"
            },{error->
                enterRequired=error is HomeworkStartRequired
                state.text=if(error is ApiProblem || error is LoginRequired || error is HomeworkStartRequired)error.message.orEmpty() else "题目暂时无法读取，请稍后重试。"
            })
            render()
        }
    }
    private fun copyText(question:HomeworkQuestion?=null) {
        if(!valid())return
        val work=item ?: return;val set=questions ?: return
        if(set.questions.isEmpty() || (question==null && (reading || (expectedQuestions!=null && set.questions.size!=expectedQuestions))))return
        try {
            val text=set.exportText(work.title,work.courseName,question)
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("作业题目文字",text))
            state.text=if(question==null)"已复制 ${set.questions.size} 道题的文字；[图片] 编号对应本次图片顺序。" else "已复制第 ${question.number} 题的文字。"
        }catch(e:Exception){state.text=if(e is ApiProblem)e.message.orEmpty() else "复制失败，文字较多时可按单题复制。"}
    }
    private fun saveImages(urls:List<String>,prefix:String) {
        if(!valid() || reading || saving || working || urls.isEmpty())return
        if(HomeworkImageSaver.requiresLegacyPermission(this)) {
            pendingImages=urls.toList();pendingPrefix=prefix
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),LEGACY_WRITE);return
        }
        saving=true;lastSave=null;render()
        val owner=sessionOwner
        val token=++saveGeneration
        work("准备保存 ${urls.distinct().size} 张题图…",{
            try {
                fun progress(done:Int,total:Int){main.post{if(valid() && sessionOwner==owner && saving && token==saveGeneration)state.text="正在保存 $done / $total 张…"}}
                val result=imageSaveAction?.invoke(urls,prefix,::progress) ?: saver!!.save(this,urls,prefix,::progress)
                Result.success(result)
            }catch(e:java.util.concurrent.CancellationException){throw e}
            catch(e:Exception){Result.failure<HomeworkImageSaveResult>(e)}
        }) { result->
            saving=false
            result.fold({saved->lastSave=saved;state.text="保存完成：${saved.saved} / ${saved.total} 张，位置：相册 / ClassBell。"},
                {error->state.text=if(error is ApiProblem || error is LoginRequired)error.message.orEmpty() else "保存未完成，请按题重试。"})
            render()
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode!=LEGACY_WRITE)return
        val urls=pendingImages;val prefix=pendingPrefix;pendingImages=null;pendingPrefix=""
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED && urls!=null)saveImages(urls,prefix)
        else state.text="未允许相册写入，图片未下载。Android 8 / 9 保存图片时需要此权限；文字仍可复制。"
    }
    override fun onDestroy(){reader?.cancel();saver?.close();super.onDestroy()}
    companion object {
        private const val LEGACY_WRITE=6820
        fun intent(activity:Activity,item:HomeworkItem,owner:String=(activity.application as BellApp).prefs.getString("identity","").orEmpty())=Intent(activity,HomeworkExportActivity::class.java)
            .putExtra("owner",owner)
            .putExtra("homework",item.json().toString())
    }
}
