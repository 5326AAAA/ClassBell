package cn.classbell.app

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class StudyFeaturesTest {
    private val course=Course("101","201","合成课程","测试教师","一班","301")
    @Test fun courseMetadataSurvivesStorageAndCourseLinksAreBoundToTheChosenClass() {
        assertEquals(course,Course.fromJson(course.json()))
        assertEquals("",Course.fromJson(JSONObject("""{"id":"1","classId":"2","name":"旧课程"}""")).teacher)
        val url=CourseLinks.content(course)
        assertTrue(url.contains("courseid=101"));assertTrue(url.contains("clazzid=201"));assertTrue(url.contains("cpi=301"))
        assertTrue(OfficialPagePolicy.initial(url))
    }
    @Test fun activityFeedKeepsEndedAndCurrentItemsWithoutInventingAnOngoingState() {
        val items=CourseActivity.parse("""{"activeList":[{"id":11,"activeType":2,"otherId":4,"nameOne":"合成位置签到","status":1},{"id":12,"activeType":2,"timeLine":"结束时间：10-09","otherId":0},{"id":13,"activeType":42,"nameOne":"合成课堂任务"}]}""",course)
        assertEquals(3,items.size);assertEquals("位置签到",items[0].typeLabel);assertEquals(1,items[0].status)
        assertEquals(2,items[1].status);assertEquals(0,items[2].status)
        assertThrows(ApiProblem::class.java){CourseActivity.parse("{}",course)}
        assertTrue(CourseActivity.parse("{\"activeList\":[]}",course).isEmpty())
    }
    @Test fun embeddedPagesRejectExternalOrNonHttpsDestinationsAndOnlyReturnChosenContentUris() {
        for(url in listOf("javascript:alert(1)","http://mooc1-api.chaoxing.com/work/stu-work","https://evil.example/work/stu-work","https://mooc1-api.chaoxing.com.evil.example/work/stu-work","https://mooc1-api.chaoxing.com/unrecognized"))assertFalse(OfficialPagePolicy.initial(url))
        assertTrue(OfficialPagePolicy.initial(HomeworkParser.WEB_URL))
        assertTrue(OfficialPagePolicy.allowed("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu"))
        assertFalse(OfficialPagePolicy.allowed("https://mooc2-ans.chaoxing.com.evil.example/"))
        assertEquals("https://mooc1-api.chaoxing.com/work/stu-work",OfficialPagePolicy.navigation("http://mooc1-api.chaoxing.com/work/stu-work"))
        assertNull(OfficialPagePolicy.navigation("http://evil.example/"))
        assertNull(OfficialPagePolicy.navigation("https://mooc1-api.chaoxing.com:8443/work/stu-work"))
        val chosen=Uri.parse("content://documents/selected")
        assertEquals(listOf(chosen),OfficialPagePolicy.files(listOf(chosen,chosen,Uri.parse("file:///private/example"),Uri.parse("https://example.com/remote"))))
    }
    @Test fun courseFeedUsesModernOwnedCourseEndpointAndKeepsHistoricalStatus() {
        val app=RuntimeEnvironment.getApplication() as BellApp
        val cookies=SessionCookies(app.vault,null).apply{replaceWith(listOf(okhttp3.Cookie.Builder().name("_uid").value("123").domain("chaoxing.com").path("/").secure().build()))}
        val api=ChaoxingApi(cookies)
        val transport=okhttp3.OkHttpClient.Builder().addInterceptor{chain->
            val request=chain.request();assertEquals("/v2/apis/active/student/activelist",request.url.encodedPath)
            assertEquals("101",request.url.queryParameter("courseId"));assertEquals("201",request.url.queryParameter("classId"))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"result":1,"data":{"activeList":[{"id":"401","activeType":2,"status":2,"nameOne":"合成历史签到"}]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        ChaoxingApi::class.java.getDeclaredField("client").apply{isAccessible=true}.set(api,transport)
        try{val items=api.activityFeed(course);assertEquals(1,items.size);assertEquals(2,items.single().status)}finally{api.cancelRequests();transport.connectionPool.evictAll();transport.dispatcher.executorService.shutdown()}
    }
    @Test fun accountProfileChecksServerIdentityAndSessionsNeverShareCookies() {
        assertEquals("合成学生",AccountProfile.parse("""{"msg":{"uid":"1","puid":"2","name":"合成学生"}}""","2").name)
        assertThrows(LoginRequired::class.java){AccountProfile.parse("""{"msg":{"uid":"3","name":"合成学生"}}""","2")}
        assertThrows(ApiProblem::class.java){AccountProfile.parse("""{"msg":{"uid":"2","name":""}}""","2")}
        val app=RuntimeEnvironment.getApplication() as BellApp
        val a=SessionCookies(app.vault,null);val b=SessionCookies(app.vault,null)
        fun cookie(uid:String)=okhttp3.Cookie.Builder().name("_uid").value(uid).domain("chaoxing.com").path("/").secure().build()
        a.replaceWith(listOf(cookie("1")));b.replaceWith(listOf(cookie("2")))
        a.clear();assertEquals("2",b.userId());assertNull(a.userId())
    }
    @Test fun currentLocationRejectsStaleInaccurateMockAndInvalidFixes() {
        LocationFix(1.3,103.8,10f,1000).requireCurrent(2000)
        for(fix in listOf(LocationFix(1.3,103.8,10f,1000,true),LocationFix(100.0,10.0,1f,1000),LocationFix(1.0,2.0,1000f,1000),LocationFix(Double.NaN,2.0,1f,1000)))assertThrows(ApiProblem::class.java){fix.requireCurrent(2000)}
        assertThrows(ApiProblem::class.java){LocationFix(1.3,103.8,10f,1000).requireCurrent(130_000)}
    }
}
