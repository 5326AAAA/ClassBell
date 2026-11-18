package cn.classbell.app

import android.os.SystemClock
import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class LocationSigningTest {
    private val sign=SignActivity("301","合成位置签到",Course("101","201","合成课程"),otherId=4)
    private val cookies=listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build())
    private fun details()=JSONObject().put("result",1).put("data",JSONObject().put("id",301).put("courseId",101).put("clazzid",201).put("activeType",2).put("status",1).put("otherId",4).put("ifphoto",0).put("ifopenAddress",1).put("ifNeedVCode",0).put("showVCode",0).put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0).put("openPreventCheatFlag",0).put("openSignOutFlag",0).put("configJson","{}")).toString()
    private fun response(request:Request)=when(request.url.encodedPath){
        "/v2/apis/active/student/activelist"->"""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":4,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo"->details()
        "/apis/login/userLogin4Uname.do"->"""{"msg":{"uid":"900","puid":"901","name":"合成学生","fid":1874}}"""
        "/newsign/preSign"->"<html>准备页面</html>"
        "/pptSign/stuSignajax"->"success"
        else->throw AssertionError("Unexpected synthetic request")
    }
    @Test fun manualLocationUsesOnlyTheProvidedFreshFixAndDoesNotRelaxAutomaticSigningRules() {
        val seen=mutableListOf<Request>();val fix=LocationFix(1.3,103.8,12f,SystemClock.elapsedRealtime())
        assertEquals(SignMode.OFFICIAL,SignProtocol.detail(details(),sign).mode)
        val api=SigningApi(cookies,{},allowLocation=true){seen+=it;response(it)}
        try {
            assertTrue(api.submitLocation(sign,fix).successful)
            val sent=seen.single{it.url.encodedPath=="/pptSign/stuSignajax"}
            assertEquals("1.3",sent.url.queryParameter("latitude"));assertEquals("103.8",sent.url.queryParameter("longitude"))
            assertEquals("901",sent.url.queryParameter("uid"));assertNull(sent.url.queryParameter("validate"));assertNull(sent.url.queryParameter("deviceId"))
        }finally{api.cancel()}
    }
    @Test fun invalidLocationAndAdditionalVerificationNeverReachCommit() {
        var commits=0
        val api=SigningApi(cookies,{},allowLocation=true){request->
            if(request.url.encodedPath=="/pptSign/stuSignajax")commits++
            if(request.url.encodedPath.endsWith("getPPTActiveInfo"))JSONObject(details()).apply{getJSONObject("data").put("openCheckFaceFlag",1)}.toString() else response(request)
        }
        try {
            assertThrows(ApiProblem::class.java){api.submitLocation(sign,LocationFix(1.3,103.8,10f,SystemClock.elapsedRealtime(),true))}
            assertThrows(ApiProblem::class.java){api.submitLocation(sign,LocationFix(1.3,103.8,10f,SystemClock.elapsedRealtime()))}
            assertEquals(0,commits)
        }finally{api.cancel()}
    }
    @Test fun manualCoordinatesAreSentAsChosenWithoutInventingGpsOrVerificationProof() {
        val seen=mutableListOf<Request>();val point=ManualLocation(1.3,103.8,"合成测试地点")
        val api=SigningApi(cookies,{},allowLocation=true){seen+=it;response(it)}
        try {
            assertTrue(api.submitManualLocation(sign,point).successful)
            val sent=seen.single{it.url.encodedPath=="/pptSign/stuSignajax"}
            assertEquals("1.3",sent.url.queryParameter("latitude"));assertEquals("103.8",sent.url.queryParameter("longitude"))
            assertEquals(point.address,sent.url.queryParameter("address"));assertNull(sent.url.queryParameter("validate"));assertNull(sent.url.queryParameter("deviceId"));assertNull(sent.url.queryParameter("accuracy"))
        }finally{api.cancel()}
    }
    @Test fun manualSelectionDoesNotSubmitWhenTheActivityEndedOrRequirementsChanged() {
        for(ended in listOf(false,true)) {
            var commits=0
            val api=SigningApi(cookies,{},allowLocation=true){request->
                if(request.url.encodedPath=="/pptSign/stuSignajax")commits++
                when {
                    ended && request.url.encodedPath.endsWith("activelist")->"""{"result":1,"data":{"activeList":[]}}"""
                    !ended && request.url.encodedPath.endsWith("getPPTActiveInfo")->JSONObject(details()).apply{getJSONObject("data").put("openCheckFaceFlag",1)}.toString()
                    else->response(request)
                }
            }
            try{assertThrows(ApiProblem::class.java){api.submitManualLocation(sign,ManualLocation(1.3,103.8))};assertEquals(0,commits)}finally{api.cancel()}
        }
    }
}
