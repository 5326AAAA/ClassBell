package cn.classbell.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class HomeworkApiTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    private val now=Instant.parse("2026-11-05T04:56:00Z").toEpochMilli()
    private val course=Course("101","201","合成课程")
    private fun row(id:Int,status:String="未提交",time:String="")="""<li data="https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial?courseId=101&amp;clazzId=201&amp;taskrefId=$id&amp;type=work"><div role=option><p>合成作业$id</p><span class=status>$status</span><span class=time>$time</span></div></li>"""
    private fun client(reply:(Request)->Pair<Int,String>)=OkHttpClient.Builder().addInterceptor{chain->
        val request=chain.request();assertEquals("GET",request.method)
        val result=reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(result.first).message("synthetic")
            .apply{if(result.first in 300..399)header("Location",result.second)}
            .body((if(result.first in 300..399)"" else result.second).toResponseBody("text/html".toMediaType())).build()
    }.build()
    private fun cookies()=SessionCookies(app.vault,null)
    private fun close(api:HomeworkApi,transport:OkHttpClient){api.cancel();transport.connectionPool.evictAll();transport.dispatcher.executorService.shutdown()}
    @Test fun courseTaskAppearsWithoutAutomaticallyOpeningItsAttendRoute() {
        val paths=mutableListOf<String>()
        val transport=client { request->paths+=request.url.encodedPath
            200 to """<li data="https://mobilelearn.chaoxing.com/widget/renwu/proxyRenwuAttendUrl?activeId=901"><div role="option"><p>实验1</p><span class="status">未完成</span><span>《合成课程》</span></div></li>"""
        }
        val api=HomeworkApi(cookies(),transport){now}
        try {
            val result=api.fetch(listOf(course));assertTrue(result.items.single().activityTask)
            assertEquals(listOf("/work/stu-work"),paths);assertEquals(0,result.skippedRows)
        }finally{close(api,transport)}
    }
    @Test fun alreadyGradedReadOnlyRedirectCanSupplyDeadline() {
        val paths=mutableListOf<String>()
        val transport=client { request->paths+=request.url.encodedPath
            when(request.url.encodedPath) {
                "/work/stu-work"->200 to row(1)
                "/mooc-ans/android/mtaskmsgspecial"->302 to "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestionYiPiYue?workId=1"
                else->200 to "截止时间：2026-11-10 20:00"
            }
        }
        val api=HomeworkApi(cookies(),transport){now}
        try {assertNotNull(api.fetch(listOf(course)).items.single().dueAt);assertEquals(3,paths.size)}finally{close(api,transport)}
    }
    @Test fun refreshReadsMissingDeadlinesButDoesNotFetchAlreadySubmittedOrKnownDates(){
        val requests=mutableListOf<Request>()
        val transport=client{request->requests+=request
            if(request.url.encodedPath=="/work/stu-work")200 to (row(1)+row(2,"已提交")+row(3,time="截止时间：2026-11-10 20:00"))
            else 200 to "<p>开始时间：10-26 14:38</p><p>截止时间：10-30 15:48</p>"
        };val api=HomeworkApi(cookies(),transport){now}
        try{
            val items=api.fetch(listOf(course)).items
            assertEquals(listOf(HomeworkState.EXPIRED,HomeworkState.SUBMITTED,HomeworkState.PENDING),items.map{it.state(now)})
            assertEquals(2,requests.size);assertEquals("1",requests.last().url.queryParameter("taskrefId"))
            assertTrue(items.first().deadlineText()!!.contains("2026-10-30 15:48"))
        }finally{close(api,transport)}
    }
    @Test fun previewRedirectIsReadButAnswerSubmissionAndOtherHostsAreNeverFollowed(){
        for(destination in listOf("https://mooc1-api.chaoxing.com/mooc-ans/work/phone/task?workId=1","http://mooc1-api.chaoxing.com/mooc-ans/work/phone/task?workId=1","https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?workId=1","https://example.invalid/preview")) {
            val requests=mutableListOf<Request>();val transport=client{request->requests+=request
                when(request.url.encodedPath){"/work/stu-work"->200 to row(1);"/mooc-ans/android/mtaskmsgspecial"->302 to destination;else->200 to "截止时间：2026-10-30 15:48"}
            };val api=HomeworkApi(cookies(),transport){now}
            try{val item=api.fetch(listOf(course)).items.single()
                if(destination.contains("/phone/task")){assertEquals(3,requests.size);assertTrue(requests.last().url.isHttps);assertEquals(HomeworkState.EXPIRED,item.state(now))}
                else {assertEquals(2,requests.size);assertNull(item.dueAt);assertTrue(item.remaining(now).contains("未读取"))}
            }finally{close(api,transport)}
        }
    }
    @Test fun detailFailuresKeepKnownDeadlineAndNewUnknownDeadlinesAreLabelledHonestly(){
        val old=HomeworkParser.parse(row(1,time="截止时间：2026-10-30 15:48"),listOf(course),now-1000)
        val transport=client{if(it.url.encodedPath=="/work/stu-work")200 to (row(1)+row(2)) else 404 to "没有返回可读详情"}
        val api=HomeworkApi(cookies(),transport){now}
        try{
            val items=api.fetch(listOf(course),old).items
            assertEquals(HomeworkState.EXPIRED,items.first().state(now));assertTrue(items.first().deadlineText()!!.startsWith("上次读取"))
            assertNull(items.last().dueAt);assertTrue(items.last().remaining(now).contains("截止时间未读取"))
        }finally{close(api,transport)}
    }
    @Test fun largerListsContinueUnattemptedDetailsOnTheNextManualRefresh(){
        var listRequest=0;val queried=mutableListOf<Int>()
        val transport=client{request->if(request.url.encodedPath=="/work/stu-work"){listRequest++;200 to (1..43).joinToString(""){row(it)}}
            else {queried+=request.url.queryParameter("taskrefId")!!.toInt();200 to "截止时间暂未提供"}}
        val api=HomeworkApi(cookies(),transport){now+listRequest*1000}
        try{
            val first=api.fetch(listOf(course));assertEquals(40,queried.size)
            queried.clear();api.fetch(listOf(course),first)
            assertEquals(listOf(41,42,43),queried.take(3));assertEquals(40,queried.size)
        }finally{close(api,transport)}
    }
    @Test fun cancellationAndLoginRedirectDoNotPublishPartialRefreshes(){
        lateinit var api:HomeworkApi
        val transport=client{if(it.url.encodedPath=="/work/stu-work")200 to row(1) else {api.cancel();200 to "截止时间：2026-10-30 15:48"}}
        api=HomeworkApi(cookies(),transport){now}
        try{assertThrows(CancellationException::class.java){api.fetch(listOf(course))}}finally{close(api,transport)}
        val login=client{if(it.url.encodedPath=="/work/stu-work")200 to row(1) else 302 to "https://passport2.chaoxing.com/login"}
        val expired=HomeworkApi(cookies(),login){now}
        try{assertThrows(LoginRequired::class.java){expired.fetch(listOf(course))}}finally{close(expired,login)}
    }
}
