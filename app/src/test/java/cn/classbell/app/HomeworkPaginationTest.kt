package cn.classbell.app

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class HomeworkPaginationTest {
    private val entry="https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?workId=901&courseId=101&classId=201&enc=page%2Fsignature"
    private val item=HomeworkItem("901","分页作业","合成课程","101:201","未提交","",null,0,false,entry)
    private fun page(index:Int,total:Int=3,id:Int=901+index)="""
        <div class=singleQuesId><form><input type=hidden id=index value=$index>
        <input type=hidden id=workRelationAnswerId value=801><input type=hidden id=questionId value=$id>
        <h2 class=titType>简答题</h2><div class=workWrap>分页题${index+1}<img src='/image-${index+1}.png'></div>
        <div class=Briefanswer>private-saved-answer</div></form></div>
        <ul class=AnswerCard><li class=spancard><b>${index+1}/$total</b></li></ul>
        <script>function getTheNextQuestion(){submitForm(true,callback)}</script>
    """.trimIndent()
    private fun transport(answer:(Request)->String)=OkHttpClient.Builder().addInterceptor { chain->
        val request=chain.request();assertEquals("GET",request.method)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body(answer(request).toResponseBody("text/html".toMediaType())).build()
    }.build()
    private fun close(source:HomeworkDetailSource,client:OkHttpClient){source.cancel();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}

    @Test fun opensEveryPageFromTheAnswerCardEvenWhenServerStartsAtTheLastQuestion() {
        val requests=mutableListOf<Request>();val progress=mutableListOf<Pair<Int,Int>>()
        val client=transport{request->requests+=request;page(request.url.queryParameter("index")?.toInt() ?: 2)}
        val source=HomeworkDetailSource(emptyList(),{},client)
        try {
            val result=source.load(item,begin=true){done,total->progress+=done to total}
            val set=HomeworkQuestionParser.parse(result.html,result.url)
            assertEquals(3,result.expectedQuestions);assertEquals(3,set.questions.size)
            assertTrue(set.exportText("","").indexOf("分页题1")<set.exportText("","").indexOf("分页题3"))
            assertFalse(set.exportText("","").contains("private-saved"))
            assertEquals(listOf(null,"0","1"),requests.map{it.url.queryParameter("index")})
            requests.drop(1).forEach{assertEquals("801",it.url.queryParameter("workAnswerId"));assertEquals("page/signature",it.url.queryParameter("enc"))}
            assertEquals(listOf(1 to 3,2 to 3,3 to 3),progress)
            assertEquals(listOf(1,2,3),set.questions.map{it.number})
        }finally{close(source,client)}
    }
    @Test fun serverIgnoringIndexCannotProduceAnApparentlyCompleteCopy() {
        val client=transport{page(2)};val source=HomeworkDetailSource(emptyList(),{},client)
        try{assertThrows(ApiProblem::class.java){source.load(item,true)}}finally{close(source,client)}
    }
    @Test fun continuationDoesNotReuseTheEntryFlagThatResumesTheLastVisitedQuestion() {
        val requests=mutableListOf<Request>()
        val client=transport{request->
            requests+=request
            val resumed=request.url.queryParameter("checkIntegrity")=="true"
            page(if(resumed)1 else request.url.queryParameter("index")!!.toInt(),total=2)
                .replace("<form>","<form><input type=hidden id=knowledgeid value=0>")
        }
        val source=HomeworkDetailSource(emptyList(),{},client)
        try {
            val result=source.load(item.copy(url="$entry&checkIntegrity=true"),true)
            assertEquals(2,HomeworkQuestionParser.parse(result.html,result.url).questions.size)
            assertEquals(2,requests.size)
            assertEquals("true",requests.first().url.queryParameter("checkIntegrity"))
            assertNull(requests.last().url.queryParameter("checkIntegrity"))
            assertEquals("0",requests.last().url.queryParameter("knowledgeid"))
        }finally{close(source,client)}
    }
    @Test fun repeatedQuestionIdsAcrossDifferentPageNumbersAreRejected() {
        val client=transport{page(it.url.queryParameter("index")?.toInt() ?: 0,id=901)}
        val source=HomeworkDetailSource(emptyList(),{},client)
        try{assertThrows(ApiProblem::class.java){source.load(item,true)}}finally{close(source,client)}
    }
    @Test fun changedTotalsOrMissingQuestionParametersDoNotReturnPartialPapers() {
        for(kind in 0..1) {
            var calls=0
            val client=transport{request->calls++;if(calls==1)page(0) else if(kind==0)page(1,total=4) else page(1).replace("id=questionId","id=missing")}
            val source=HomeworkDetailSource(emptyList(),{},client)
            try{assertThrows(ApiProblem::class.java){source.load(item,true)}}finally{close(source,client)}
        }
    }
    @Test fun cancellationBetweenPagesStopsBeforeAnotherRequest() {
        var calls=0;val client=transport{calls++;page(0)};val source=HomeworkDetailSource(emptyList(),{},client)
        try {
            assertThrows(CancellationException::class.java){source.load(item,true){_,_->source.cancel()}}
            assertEquals(1,calls)
        }finally{close(source,client)}
    }
    @Test fun aSingleQuestionPaperNeedsNoAdditionalRequest() {
        var calls=0;val client=transport{calls++;page(0,total=1)};val source=HomeworkDetailSource(emptyList(),{},client)
        try{assertEquals(1,source.load(item,true).expectedQuestions);assertEquals(1,calls)}finally{close(source,client)}
    }
}
