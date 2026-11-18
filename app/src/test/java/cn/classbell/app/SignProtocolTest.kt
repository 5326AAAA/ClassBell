package cn.classbell.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignProtocolTest {
    private val sign=SignActivity("301","普通签到",Course("101","201","测试课程"),otherId=0)
    private val flags=listOf("ifNeedVCode","showVCode","ifopenAddress","openCheckFaceFlag","openCheckWeChatFlag","openPreventCheatFlag","openSignOutFlag")
    private fun data()=JSONObject().put("id","301").put("courseId","101").put("clazzid","201").put("activeType",2)
        .put("status",1).put("otherId",0).put("ifphoto",0).put("configJson","{}").also { d->flags.forEach { d.put(it,0) } }
    private fun plan(data:JSONObject)=SignProtocol.detail(JSONObject().put("result",1).put("data",data).toString(),sign)
    @Test fun ordinaryAndPhotoShareOtherIdButAreSeparatedByExplicitIfPhoto() {
        assertEquals(SignMode.NORMAL,plan(data()).mode)
        assertEquals(SignMode.PHOTO,plan(data().put("ifphoto",1)).mode)
        assertEquals(SignMode.OFFICIAL,plan(data().apply { remove("ifphoto") }).mode)
    }
    @Test fun everyVerificationFlagAndItsAbsencePreventsSubmission() {
        for(flag in flags) {
            assertEquals(flag,SignMode.OFFICIAL,plan(data().put(flag,1)).mode)
            assertEquals(flag,SignMode.OFFICIAL,plan(data().apply {remove(flag)}).mode)
        }
    }
    @Test fun mismatchedCoursesEndedActivitiesAndUnknownConfigurationDoNotSubmit() {
        for(d in listOf(data().put("courseId","other"),data().put("clazzid","other"),data().put("id","other"),
            data().put("status",2),data().put("activeType",42),data().put("endTime",1000),data().put("configJson","{\"newRequirement\":1}")))
            assertEquals(SignMode.OFFICIAL,plan(d).mode)
    }
    @Test fun qrLocationAndCodeVariantsStayInOfficialApp() {
        for(type in listOf(1,2,3,4,5,99)) assertEquals(SignMode.OFFICIAL,plan(data().put("otherId",type)).mode)
    }
    @Test fun exactSuccessOnlyAndUnknownReplyIsNeverEchoed() {
        assertTrue(SignProtocol.outcome("success").successful)
        assertTrue(SignProtocol.outcome("success2").successful)
        assertTrue(SignProtocol.outcome("您已签到过了").successful)
        for(text in listOf("<script>success</script>","false","secret-token=sample")) {
            val reply=SignProtocol.outcome(text)
            assertFalse(reply.successful);assertFalse(reply.message.contains(text))
        }
        assertTrue(SignProtocol.outcome("validate").message.contains("验证"))
        assertEquals(SignResultCategory.NEEDS_FIX,SignProtocol.outcome("请在学习通内签到").category)
    }
    @Test fun uploadNeedsValidObjectIdAndDoesNotAcceptArbitraryResponseUrls() {
        val id="a".repeat(32)
        assertEquals(id,SignProtocol.objectId("{\"result\":true,\"objectId\":\"$id\"}"))
        for(body in listOf("{\"objectId\":\"https://example.com/pic\"}","{\"result\":false,\"objectId\":\"$id\"}","{}"))
            assertThrows(ApiProblem::class.java) {SignProtocol.objectId(body)}
    }
    @Test fun listParserPreservesSubtypeWithoutConfusingItWithCategory() {
        val found=ApiParser.activities("""{"result":1,"data":{"activeList":[{"id":301,"activeType":2,"status":1,"otherId":"0"},{"id":302,"activeType":42,"status":1}]}}""",sign.course)
        assertEquals(0,found[0].otherId);assertNull(found[1].otherId)
    }
}
