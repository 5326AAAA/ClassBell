package cn.classbell.app

import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contract tests only; endpoint compatibility has not been tested against real attendance. */
class CodeSigningTest {
    private val activity=SignActivity("301","合成签到码",Course("101","201","合成课程"),otherId=5)
    private val cookies=listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build())
    private fun detail(flag:Int=0)=JSONObject().put("result",1).put("data",JSONObject().put("id",301).put("courseId",101).put("clazzid",201)
        .put("activeType",2).put("otherId",5).put("ifphoto",0).put("status",1).put("configJson","{}")
        .put("ifNeedVCode",flag).put("showVCode",0).put("ifopenAddress",0).put("openCheckFaceFlag",0).put("openCheckWeChatFlag",0)
        .put("openPreventCheatFlag",0).put("openSignOutFlag",0)).toString()
    private fun response(request:Request):String=when(request.url.encodedPath) {
        "/v2/apis/active/student/activelist" -> """{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"otherId":5,"status":1}]}}"""
        "/v2/apis/active/getPPTActiveInfo" -> detail()
        "/apis/login/userLogin4Uname.do" -> """{"msg":{"uid":"900","puid":"901","name":"合成学生","fid":"101"}}"""
        "/newsign/preSign" -> "<html>准备页面</html>"
        "/widget/sign/pcStuSignController/checkSignCode" -> """{"result":1}"""
        "/pptSign/stuSignajax" -> "success"
        else -> throw AssertionError("Unexpected synthetic path")
    }
    @Test fun onlyExplicitManualModeAcceptsCodeAndOfficialVerificationStillBlocksIt() {
        assertEquals(SignMode.OFFICIAL,SignProtocol.detail(detail(),activity).mode)
        assertEquals(SignMode.CODE,SignProtocol.detail(detail(),activity,true).mode)
        assertEquals(SignMode.OFFICIAL,SignProtocol.detail(detail(1),activity,true).mode)
    }
    @Test fun genuineCodeIsCheckedAndSubmittedOnceWithoutInventingValidationOrDeviceFields() {
        val seen=mutableListOf<Request>()
        val api=SigningApi(cookies,{},allowCode=true){seen+=it;response(it)}
        try {
            assertTrue(api.submitCode(activity,"123456").successful)
            val request=seen.single{it.url.encodedPath.endsWith("/stuSignajax")}
            assertEquals("123456",request.url.queryParameter("signCode"))
            assertNull(request.url.queryParameter("validate"));assertNull(request.url.queryParameter("deviceId"))
            assertEquals("mobilelearn.chaoxing.com",request.url.host)
        }finally{api.cancel()}
    }
    @Test fun rejectedCodeAndChangedRequirementsNeverReachSubmission() {
        for(changed in listOf(false,true)) {
            var submits=0
            val api=SigningApi(cookies,{},allowCode=true){request->
                if(request.url.encodedPath.endsWith("/stuSignajax"))submits++
                when {
                    changed && request.url.encodedPath.endsWith("getPPTActiveInfo") -> detail(1)
                    request.url.encodedPath.endsWith("checkSignCode") -> "{\"result\":0}"
                    else -> response(request)
                }
            }
            try{runCatching{api.submitCode(activity,"123456")};assertEquals(0,submits)}finally{api.cancel()}
        }
        assertTrue(SignProtocol.outcome("当前设备签到人数已达上限").message.contains("限制"))
        assertFalse(SignProtocol.outcome("当前设备签到人数已达上限").successful)
    }
}
