package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Question content only. Never include hidden form values, saved answers or signed URLs in copied text. */
class HomeworkQuestion(val number:Int,val kind:String,internal val body:String,val images:List<String>)
class HomeworkQuestionSet(val questions:List<HomeworkQuestion>,val skipped:Int,val unsupportedImages:Int) {
    val imageUrls:List<String> get()=questions.flatMap{it.images}.distinct()
    fun exportText(title:String,course:String,only:HomeworkQuestion?=null):String {
        val chosen=only?.let{listOf(it)} ?: questions
        val images=chosen.flatMap{it.images}.distinct().withIndex().associate{it.value to it.index+1}
        val sections=chosen.map { q->
            val text=Regex("\uE000([0-9]+)\uE001").replace(q.body){match->
                val url=q.images.getOrNull(match.groupValues[1].toInt())
                url?.let{images[it]}?.let{"[图片 $it]"} ?: "[图片]"
            }
            "第 ${q.number} 题 · ${q.kind}\n$text"
        }
        val header=listOf(title,course).filter{it.isNotBlank()}.joinToString("\n")
        val result=(listOf(header)+sections).filter{it.isNotBlank()}.joinToString("\n\n")
        if(result.length>240_000)throw ApiProblem("题目文字超过剪贴板范围，请按单题复制。")
        return result
    }
}

object HomeworkQuestionParser {
    private const val ROOTS=".questionLi, .singleQuesId"
    private const val MAX_QUESTIONS=300
    private const val MAX_IMAGES=300
    private val blocks=setOf("p","div","li","ul","ol","h1","h2","h3","h4","pre","table","tr","blockquote")
    private val documentTypes=setOf("pdf","doc","docx","ppt","pptx","xls","xlsx","zip","rar","7z","txt")
    fun parse(html:String,baseUrl:String):HomeworkQuestionSet {
        if(html.toByteArray(Charsets.UTF_8).size>2*1024*1024)throw ApiProblem("作业页面过大，未继续提取。")
        val doc=Jsoup.parse(html,baseUrl)
        val all=doc.select(ROOTS)
        val containers=all.filter{q->q.select(ROOTS).none{it!==q}}
        if(containers.size>MAX_QUESTIONS)throw ApiProblem("本页超过 $MAX_QUESTIONS 道题，请分批查看。")
        var skipped=0;var unsupported=0
        val parsed=containers.mapIndexedNotNull { index,q->
            val stem=own(q,"h3.mark_name, .mark_name, .question-title, .question-stem, .qtContent, .workWrap").firstOrNull()
            if(stem==null){skipped++;return@mapIndexedNotNull null}
            val typeText=q.attr("typename").ifBlank{own(q,".type_tit, .stemGray, .titType").firstOrNull()?.text().orEmpty()}.ifBlank{stem.text()}
            val kind=when {
                "多选" in typeText->"多选题"
                "单选" in typeText->"单选题"
                "判断" in typeText->"判断题"
                "填空" in typeText->"填空题"
                "简答" in typeText->"简答题"
                "论述" in typeText->"论述题"
                "名词解释" in typeText->"名词解释"
                "其它" in typeText || "其他" in typeText->"其它题"
                else->"题目"
            }
            val images=mutableListOf<String>()
            fun content(element:Element):String {
                val copy=element.clone()
                copy.removeAttr("aria-hidden")
                copy.select("script[type^=math/tex]").forEach{it.replaceWith(TextNode("\\("+it.data()+"\\)"))}
                copy.select("script,style,noscript,iframe,button,textarea,select,input[type=hidden],.editor-iframe,[module=insertAttach],.attach-iframe,.attachment,.workAttach,.viewAttach,.mark_answer,.analysis,.analysisDiv,.correctAnswer,.rightAnswer,.answerResult,.studentAnswer,.stuAnswerContent,.rightAnswerContent,.true_answer,.answerDiv,.wrIcon,.py_answer,.mark_score,[hidden],[aria-hidden=true]").remove()
                copy.select("a[href]").filter{a->
                    a.attr("href").substringBefore('?').substringAfterLast('.').lowercase() in documentTypes
                }.forEach{it.replaceWith(TextNode("[文档附件]"))}
                val out=StringBuilder()
                fun walk(node:Node,depth:Int) {
                    if(depth>80)throw ApiProblem("题目结构过深，未继续提取。")
                    if(node is TextNode){out.append(node.text().replace('\uE000',' ').replace('\uE001',' '));return}
                    if(node !is Element)return
                    val tag=node.normalName()
                    when(tag) {
                        "img"->{
                            val raw=listOf("data-src","data-original","data-actualsrc","data-url","src").map{node.attr(it)}.firstOrNull{it.isNotBlank()}.orEmpty()
                            val url=imageSource(raw,baseUrl)
                            if(url==null){unsupported++;out.append("[图片来源暂不支持]");return}
                            var number=images.indexOf(url)
                            if(number<0){images.add(url);number=images.lastIndex}
                            out.append('\uE000').append(number).append('\uE001');return
                        }
                        "br"->{out.append('\n');return}
                        "input"->{if(node.attr("type").lowercase() !in setOf("radio","checkbox","submit","button"))out.append("____");return}
                        "sup","sub"->{out.append(if(tag=="sup")"^(" else "_(");node.childNodes().forEach{walk(it,depth+1)};out.append(')');return}
                        "msup","msub","mfrac"->{
                            if(node.childrenSize()==2) {
                                if(tag=="mfrac")out.append('(')
                                walk(node.child(0),depth+1)
                                out.append(when(tag){"msup"->"^(";"msub"->"_(";else->")/("})
                                walk(node.child(1),depth+1);out.append(')');return
                            }
                        }
                        "msqrt"->{out.append("sqrt(");node.childNodes().forEach{walk(it,depth+1)};out.append(')');return}
                        "math"->{
                            val tex=node.attr("alttext").ifBlank{node.select("annotation[encoding*=tex]").firstOrNull()?.text().orEmpty()}
                            if(tex.isNotBlank()){out.append("\\(").append(tex).append("\\)");return}
                        }
                        "annotation"->return
                    }
                    if(tag in blocks)out.append('\n')
                    if(tag in setOf("td","th"))out.append(" | ")
                    if(tag=="pre")out.append(node.wholeText()) else node.childNodes().forEach{walk(it,depth+1)}
                    if(tag in blocks)out.append('\n')
                    if(out.length>240_000)throw ApiProblem("题目文字过多，请使用单题页面核对。")
                }
                walk(copy,0)
                return out.toString().replace('\u00a0',' ').replace("\r\n","\n").replace(Regex("[ \t]+\n"),"\n")
                    .replace(Regex("\n[ \t]+"),"\n").replace(Regex("\n{3,}"),"\n\n").trim()
            }
            val pieces=mutableListOf<String>()
            q.parents().filter{it.hasClass("questionLi") || it.hasClass("singleQuesId")}.asReversed().forEach{parent->
                own(parent,"h3.mark_name, .mark_name, .question-stem, .qtContent").firstOrNull()?.let{pieces+="【共同题干】\n"+content(it)}
            }
            pieces+=content(stem)
            val options=own(q,".answerBg, .optionCon, .Answer").filter{option->
                (!option.hasClass("Answer") || option.selectFirst("span[orichoice]")!=null) &&
                    option.parents().none{it!==q && (it.hasClass("answerBg") || it.hasClass("optionCon") || it.hasClass("Answer"))}
            }
            options.forEachIndexed { i,option->
                val answer=option.selectFirst(".answer_p, .qtDetail, .centerSpan") ?: option.clone().apply{select(".num_option,input").remove()}
                val text=content(answer)
                val inlineLabel=if(option.hasClass("optionCon"))Regex("^\\s*([A-Za-z])[.、．:：]\\s*").find(text) else null
                val label=(option.selectFirst(".num_option")?.text() ?: option.selectFirst("span[orichoice]")?.attr("orichoice"))?.trim()?.take(12)
                    ?.takeIf{it.matches(Regex("[A-Za-z][.、．:：]?"))}?.trimEnd('.','、','．',':','：')
                    ?: inlineLabel?.groupValues?.get(1) ?: (if(i<26)('A'.code+i).toChar().toString() else (i+1).toString())
                val optionText=inlineLabel?.let{text.removeRange(it.range)} ?: text
                pieces+=if(optionText.isNotBlank())"$label. $optionText" else "$label."
            }
            if(kind=="填空题") {
                val blanks=q.select(".stem_answer > .Answer").size
                if(blanks>0)pieces+="共 $blanks 个填空位置。"
            }
            val body=pieces.filter{it.isNotBlank()}.joinToString("\n")
            if(body.isBlank()){skipped++;null}else HomeworkQuestion(index+1,kind,body,images.toList())
        }
        if(parsed.flatMap{it.images}.distinct().size>MAX_IMAGES)throw ApiProblem("本页题图超过 $MAX_IMAGES 张，请分批查看。")
        return HomeworkQuestionSet(parsed,skipped,unsupported)
    }
    private fun own(q:Element,selector:String)=q.select(selector).filter{it.closest(ROOTS)===q}
    internal fun imageSource(value:String,baseUrl:String):String? {
        val raw=value.trim()
        if(raw.isEmpty())return null
        if(raw.startsWith("data:",true))return raw.takeIf{HomeworkImageSaver.supportedSource(it)}
        var url=baseUrl.toHttpUrlOrNull()?.resolve(raw) ?: return null
        if(url.scheme=="http" && url.port==80 && (url.host=="chaoxing.com" || url.host.endsWith(".chaoxing.com")))
            url=url.newBuilder().scheme("https").port(443).build()
        return url.toString().takeIf{HomeworkImageSaver.supportedSource(it)}
    }
}
