package cn.classbell.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Metadata from the actual mobile answer card; GETs do not invoke its submitForm navigation. */
internal class HomeworkPageCursor private constructor(
    val total:Int,val index:Int,val workAnswerId:String,val questionId:String,val fragment:String,
    private val base:HttpUrl
) {
    fun urlFor(index:Int):String {
        require(index in 0 until total)
        return base.newBuilder().setQueryParameter("workAnswerId",workAnswerId)
            .setQueryParameter("index",index.toString()).build().toString()
    }
    companion object {
        fun read(page:HomeworkDetailPage):HomeworkPageCursor? {
            val url=page.url.toHttpUrlOrNull() ?: return null
            if(!url.encodedPath.equals("/mooc-ans/work/phone/doHomeWork",true))return null
            val doc=Jsoup.parse(page.html,page.url)
            val counter=doc.select(".AnswerCard .spancard b").map{it.text().trim()}
                .mapNotNull{Regex("^([0-9]+)/([0-9]+)$").matchEntire(it)}.singleOrNull()
            if(counter==null) {
                if(doc.selectFirst(".AnswerCard")!=null)throw ApiProblem("作业答题卡页数无法识别，未按整份导出。")
                return null
            }
            fun hidden(id:String)=doc.getElementsByAttributeValue("id",id)
                .filter{it.normalName()=="input" && it.attr("type").equals("hidden",true)}
                .map{it.attr("value")}.distinct().singleOrNull()
            val total=counter.groupValues[2].toIntOrNull() ?: 0
            val position=counter.groupValues[1].toIntOrNull() ?: 0
            val index=hidden("index")?.toIntOrNull() ?: -1
            if(total !in 1..300 || index !in 0 until total || position!=index+1)
                throw ApiProblem("作业答题卡页码不一致，未按整份导出。")
            val answer=hidden("workRelationAnswerId")?.takeIf{it.matches(Regex("[1-9][0-9]{0,20}"))}
                ?: throw ApiProblem("作业答题记录参数缺失，未按整份导出。")
            val id=hidden("questionId")?.takeIf{it.matches(Regex("[1-9][0-9]{0,20}"))}
                ?: throw ApiProblem("作业题号参数缺失，未按整份导出。")
            val knowledge=hidden("knowledgeid")?.takeIf{it.matches(Regex("[0-9]{1,21}"))}
            val roots=doc.select(".questionLi, .singleQuesId").filter{root->
                root.parents().none{it.hasClass("questionLi") || it.hasClass("singleQuesId")}
            }
            if(roots.size!=1)throw ApiProblem("作业分页题目结构不完整，未按整份导出。")
            val root=roots.single()
            root.select("script,style,link,input[type=hidden],textarea,.Briefanswer,.stuAnswerContent,.answerDiv").remove()
            for(image in root.select("img"))for(attr in listOf("src","data-src","data-original","data-actualsrc","data-url")) {
                val value=image.attr(attr)
                if(value.isNotBlank() && !value.startsWith("data:",true))url.resolve(value)?.let{image.attr(attr,it.toString())}
            }
            // The entry link resumes the last viewed question when checkIntegrity=true.
            // The page's next-question navigation omits that entry-only flag.
            val navigation=url.newBuilder().removeAllQueryParameters("checkIntegrity")
            knowledge?.let{navigation.setQueryParameter("knowledgeid",it)}
            return HomeworkPageCursor(total,index,answer,id,root.outerHtml(),navigation.build())
        }
    }
}
