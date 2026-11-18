package cn.classbell.app

import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** All responses are synthetic; this suite never opens a network connection or submits attendance. */
class SigningApiTest {
    @Test fun resultQueryUsesOnlyReadOnlyEndpointAndChecksAccountAndActivity() {
        val confirmedAt=java.time.Instant.parse("2025-10-03T01:15:00Z").toEpochMilli()
        var payload="""{"result":1,"msg":"success","data":{"activeId":301,"uid":901,"status":1,"createtime":$confirmedAt}}"""
        val requests=mutableListOf<Request>()
        val api=SigningApi(cookies,{}){request->requests+=request;payload}
        try {
            val result=api.attendance(sign)
            assertTrue(result.successful)
            assertTrue(result.message.contains("签到时间：2025-10-03 09:15"))
            assertEquals("GET",requests.single().method)
            assertEquals("/v2/apis/sign/getAttendInfo",requests.single().url.encodedPath)
            assertEquals("301",requests.single().url.queryParameter("activeId"))
            for(changed in listOf(payload.replace("901","902"),payload.replace("301","302"),"""{"result":0,"msg":"success","data":{"activeId":301,"uid":901,"status":1}}""")) {
                payload=changed;assertEquals(SignResultCategory.UNKNOWN,api.attendance(sign).category)
            }
            assertTrue(requests.all{it.method=="GET" && it.url.encodedPath.endsWith("getAttendInfo")})
        }finally{api.cancel()}
    }
    @Test fun absentOrUnrecognizedAttendanceNeverBecomesSuccessful() {
        for(data in listOf("null","{}","""{"activeId":301,"uid":901,"status":99}""")) {
            val result=SignProtocol.attendance("""{"result":1,"data":$data}""",sign,"901")
            assertFalse(result.successful);assertEquals(SignResultCategory.UNKNOWN,result.category)
        }
        val notSigned=SignProtocol.attendance("""{"result":1,"data":{"activeId":301,"uid":901,"status":0}}""",sign,"901")
        assertEquals(SignResultCategory.NOT_SUBMITTED,notSigned.category);assertFalse(notSigned.retryAllowed)
    }
    @Test fun overviewAcceptsOnlyMatchedActivityMetadataAndDoesNotInventMissingCounts() {
        val data=JSONObject(detail(1)).getJSONObject("data")
            .put("name","合成拍照签到").put("status",2)
            .put("startTime",1735693200L).put("endtime",1735695000000L).put("attendNum",85)
        val row=SignOverview.read(data,sign)!!
        assertEquals("合成拍照签到",row.name)
        assertEquals("拍照签到",row.type)
        assertEquals(2,row.status)
        assertEquals(1735693200000L,row.startsAt)
        assertEquals(1735695000000L,row.endsAt)
        assertEquals(85,row.attendedCount)
        data.remove("attendNum")
        assertNull(SignOverview.read(data,sign)!!.attendedCount)
        data.put("clazzid","202")
        assertNull(SignOverview.read(data,sign))
    }
    private val sign=SignActivity("301","合成签到",Course("101","201","合成课程"),otherId=0)
    private val cookies=listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build(),
        Cookie.Builder().name("fid").value("1874").domain("chaoxing.com").path("/").secure().build())
    private fun detail(photo:Int)=JSONObject().put("result",1).put("data",JSONObject()
        .put("id","301").put("courseId","101").put("clazzid","201").put("activeType",2).put("status",1).put("otherId",0).put("ifphoto",photo)
        .put("ifNeedVCode",0).put("showVCode",0).put("ifopenAddress",0).put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0)
        .put("openPreventCheatFlag",0).put("openSignOutFlag",0).put("configJson","{}")).toString()
    private fun response(request:Request,photo:Int=0):String=when(request.url.encodedPath) {
        "/v2/apis/active/student/activelist" -> """{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":0,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo" -> detail(photo)
        "/apis/login/userLogin4Uname.do" -> """{"msg":{"uid":"900","puid":"901","name":"合成学生 & +","fid":1874}}"""
        "/newsign/preSign" -> "<html>准备页面</html>"
        "/api/token/uservalid" -> """{"_token":"synthetic-upload-token"}"""
        "/upload" -> """{"result":true,"objectId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        "/pptSign/stuSignajax" -> "success"
        else -> throw AssertionError("Unexpected synthetic request path")
    }
    @Test fun ordinarySubmissionUsesMatchedPuidAndEncodedProfileWithoutPhotoOrOtherDestinations() {
        val seen=mutableListOf<Request>()
        val api=SigningApi(cookies,{}) {request->seen+=request;response(request)}
        try {
            assertTrue(api.submit(sign).successful)
            val submit=seen.single{it.url.encodedPath=="/pptSign/stuSignajax"}
            assertEquals("901",submit.url.queryParameter("uid"))
            assertEquals("合成学生 & +",submit.url.queryParameter("name"))
            assertEquals("1874",submit.url.queryParameter("fid"));assertNull(submit.url.queryParameter("objectId"))
            assertFalse(seen.any{it.url.host=="pan-yz.chaoxing.com"})
            assertTrue(seen.all{it.url.isHttps && it.url.host in ChaoxingProtocol.hosts})
        } finally{api.cancel()}
    }
    @Test fun changedPhotoRequirementIsCaughtBeforeFinalSubmission() {
        var details=0;var submissions=0
        val api=SigningApi(cookies,{}) {request->
            if(request.url.encodedPath=="/pptSign/stuSignajax") submissions++
            if(request.url.encodedPath=="/v2/apis/active/getPPTActiveInfo") detail(if(++details==1)0 else 1) else response(request)
        }
        try {assertThrows(ApiProblem::class.java){api.submit(sign)};assertEquals(0,submissions)}finally{api.cancel()}
    }
    @Test fun photoWithoutCourseIdIsConfirmedByItsOwnClassListOnly() {
        val detail = JSONObject(detail(1)).apply {
            getJSONObject("data").apply {
                put("courseId", JSONObject.NULL)
                put("configJson", """{"sendToOtherClassList":"","onlyAttendForCurrentClass":0,"ifSendToOtherClass":0}""")
            }
        }
        var activeInClass = true
        var checks = 0
        val api = SigningApi(cookies, {}) { request ->
            when (request.url.encodedPath) {
                "/v2/apis/active/getPPTActiveInfo" -> detail.toString()
                "/v2/apis/active/student/activelist" -> {
                    checks++
                    assertEquals(sign.course.id, request.url.queryParameter("courseId"))
                    assertEquals(sign.course.classId, request.url.queryParameter("classId"))
                    assertEquals("1874", request.url.queryParameter("fid"))
                    if (activeInClass) """{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"status":1}]}}"""
                    else """{"result":1,"data":{"activeList":[]}}"""
                }
                else -> throw AssertionError("Planning must only read activity detail and list")
            }
        }
        try {
            assertEquals(SignMode.PHOTO, api.plan(sign).mode)
            activeInClass = false
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            activeInClass = true
            val data = detail.getJSONObject("data")
            data.put("status", 2)
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            data.put("status", 1)
            data.put("configJson", """{"sendToOtherClassList":"","onlyAttendForCurrentClass":1,"ifSendToOtherClass":0}""")
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            data.put("configJson", """{"sendToOtherClassList":"","onlyAttendForCurrentClass":0,"ifSendToOtherClass":0}""")
            data.put("courseId", "999")
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            data.put("courseId", JSONObject.NULL).put("clazzid", "999")
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            data.put("clazzid", sign.course.classId).put("ifNeedVCode", 1)
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            data.put("ifNeedVCode", 0).put("configJson", """{"sendToOtherClassList":"[]","ifSendToOtherClass":1}""")
            assertEquals(SignMode.OFFICIAL, api.plan(sign).mode)
            assertEquals(2, checks)
        } finally { api.cancel() }
    }
    @Test fun mismatchedIdentityPreventsPreparationAndSubmission() {
        val paths=mutableListOf<String>()
        val api=SigningApi(cookies,{}) {request->paths+=request.url.encodedPath
            if(request.url.host=="sso.chaoxing.com") """{"msg":{"uid":"123","puid":"456","name":"其他合成用户"}}""" else response(request)
        }
        try {assertThrows(LoginRequired::class.java){api.submit(sign)};assertFalse(paths.contains("/newsign/preSign"));assertFalse(paths.contains("/pptSign/stuSignajax"))}finally{api.cancel()}
    }
    @Test fun photoUploadIsBoundToOfficialHostAndObjectIdIsSentOnlyAfterSuccessfulUpload() {
        val seen=mutableListOf<Request>();var commits=0
        val file=File.createTempFile("synthetic-sign-photo-",".jpg")
        file.writeBytes(byteArrayOf(1,2,3))
        val api=SigningApi(cookies,{}){request->seen+=request;response(request,1)}
        try {
            val result=api.submit(sign,file){commits++}
            assertTrue(result.successful);assertEquals(PhotoTransferState.CONFIRMED,result.photoState)
            val upload=seen.single{it.url.encodedPath=="/upload"}
            assertEquals("pan-yz.chaoxing.com",upload.url.host);assertEquals("POST",upload.method)
            assertEquals("mobilelearn",upload.url.queryParameter("_from"))
            assertTrue(upload.body is okhttp3.MultipartBody)
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",seen.last().url.queryParameter("objectId"))
            assertEquals(1,commits)
        }finally{api.cancel();file.delete()}
    }
    @Test fun unknownUploadResponseNeverCallsSignEndpointAndLostSubmissionReplyIsNotRetried() {
        var submits=0
        val file=File.createTempFile("synthetic-sign-photo-",".jpg").apply{writeBytes(byteArrayOf(1))}
        val api=SigningApi(cookies,{}){request->
            if(request.url.encodedPath=="/pptSign/stuSignajax") submits++
            if(request.url.encodedPath=="/upload") "{}" else response(request,1)
        }
        try{assertEquals(PhotoTransferState.UNCONFIRMED,assertThrows(PhotoTransferException::class.java){api.submit(sign,file)}.photoState);assertEquals(0,submits)}finally{api.cancel();file.delete()}
        val lost=SigningApi(cookies,{}){request->
            if(request.url.encodedPath=="/pptSign/stuSignajax"){submits++;throw IOException("synthetic connection loss")}
            response(request)
        }
        try{assertThrows(IOException::class.java){lost.submit(sign)};assertEquals(1,submits)}finally{lost.cancel()}
    }
    @Test fun faceVerificationIsNotMistakenForAnOrdinaryPhotoUpload() {
        val seen=mutableListOf<Request>()
        val file=File.createTempFile("synthetic-face-",".jpg").apply{writeBytes(byteArrayOf(1))}
        val api=SigningApi(cookies,{}) { request ->
            seen+=request
            if(request.url.encodedPath.endsWith("getPPTActiveInfo"))JSONObject(detail(1)).apply{getJSONObject("data").put("openCheckFaceFlag",1)}.toString()
            else response(request,1)
        }
        try {
            assertThrows(ApiProblem::class.java){api.submit(sign,file)}
            assertFalse(seen.any{it.url.host=="pan-yz.chaoxing.com" || it.url.encodedPath=="/pptSign/stuSignajax"})
        } finally {api.cancel();file.delete()}
    }
}
