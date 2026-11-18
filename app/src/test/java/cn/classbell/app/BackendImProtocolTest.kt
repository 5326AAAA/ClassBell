package cn.classbell.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BackendImProtocolTest {
    private val course=Course("101","201","合成课程")
    private val now=1_800_000_000_000L
    private fun meta(extension:String="{\"att_chat_course\":{\"activeId\":\"301\",\"atype\":2}}",timestamp:Long=now,namespace:Long=1):ByteArray {
        val ext=ImPb.Writer().text(1,"attachment").number(2,8).text(6,extension).build()
        val content=ImPb.Writer().number(1,0).text(2,"合成签到提示").build()
        val body=ImPb.Writer().number(1,2).bytes(4,content).bytes(5,ext).build()
        return ImPb.Writer().number(1,99).number(4,timestamp).number(5,namespace).bytes(6,body).build()
    }
    private fun detail(type:Int=2,status:Int=1)=JSONObject().put("result",1).put("data",JSONObject()
        .put("id","301").put("courseId","101").put("clazzid","201").put("activeType",type).put("status",status).put("otherId",0).put("name","合成活动")).toString()
    @Test fun authenticationEncodingMatchesThePublicDescriptorIndependentPythonEncoder() {
        val expected="CAASQgoOY3gtZGV2I2N4c3R1ZHkSDnN5bnRoZXRpYy11c2VyGgtlYXNlbW9iLmNvbSITd2ViaW1fMTcwMDAwMDAwMDAwMBoSJHQkc3ludGhldGljLXRva2VuQANKUggQEgUzLjAuMCgAMABKE3dlYmltXzE3MDAwMDAwMDAwMDBiBXdlYmltahN3ZWJpbV8xNzAwMDAwMDAwMDAwchIkdCRzeW50aGV0aWMtdG9rZW5QAFgA"
        assertArrayEquals(Base64.getDecoder().decode(expected),BackendImProtocol.provision("synthetic-user","synthetic-token","webim_1700000000000"))
    }
    @Test fun nestedAttachmentReferenceIsParsedButOrdinaryTextAndUnrelatedNamespacesAreNot() {
        assertEquals(setOf("301"),BackendImProtocol.event(meta())!!.activityIds)
        assertNull(BackendImProtocol.event(meta("{}")))
        assertNull(BackendImProtocol.event(meta(namespace=2)))
        val noId=meta("{\"text\":\"请大家签到，今天上课\"}")
        assertNull(BackendImProtocol.event(noId))
    }
    @Test fun allowedActivityUrlIsOnlyAReferenceAndDoesNotCauseNavigation() {
        val body=JSONObject().put("url","https://mobilelearn.chaoxing.com/newsign/preSign?activePrimaryId=301&courseId=101").toString()
        assertEquals(setOf("301"),BackendImProtocol.event(meta(body))!!.activityIds)
        assertNull(BackendImProtocol.event(meta(body.replace("mobilelearn.chaoxing.com","example.com"))))
    }
    @Test fun oldAndFutureMessagesAreRejectedAndSecondTimestampsAreNormalized() {
        assertTrue(BackendImProtocol.fresh(BackendImProtocol.event(meta())!!,now,now))
        assertFalse(BackendImProtocol.fresh(BackendImProtocol.event(meta(timestamp=now-31_000))!!,now,now))
        assertFalse(BackendImProtocol.fresh(BackendImProtocol.event(meta(timestamp=now+61_000))!!,now,now))
        assertEquals(now,BackendImProtocol.event(meta(timestamp=now/1000))!!.timestamp)
    }
    @Test fun protobufRejectsTruncationInvalidLengthsAndUnsupportedWireTypes() {
        for(bytes in listOf(byteArrayOf(0),byteArrayOf(10,100,1),byteArrayOf(8,0x80.toByte()),byteArrayOf(15,0)))
            assertThrows(IllegalArgumentException::class.java){ImPb.read(bytes)}
        assertThrows(IllegalArgumentException::class.java){ImPb.read(ByteArray(2*1024*1024+1))}
    }
    @Test fun sockJsFramesRoundTripAndDoNotTreatOpenOrHeartbeatAsMessages() {
        val raw=BackendImProtocol.envelope(1)
        val frame="a"+BackendImProtocol.frame(raw)
        assertArrayEquals(raw,BackendImProtocol.packets(frame).single())
        assertTrue(BackendImProtocol.packets("h").isEmpty());assertTrue(BackendImProtocol.packets("o").isEmpty())
        assertThrows(IllegalArgumentException::class.java){BackendImProtocol.packets("a[\"invalid base64\"]")}
    }
    @Test fun officialDetailsMustMatchTheReferenceSelectedCourseActiveTypeAndState() {
        assertEquals(ReminderKind.SIGN_IN,BackendImProtocol.verified(detail(),"301",listOf(course),now)!!.kind)
        assertEquals(ReminderKind.CLASSROOM,BackendImProtocol.verified(detail(42),"301",listOf(course),now)!!.kind)
        assertNull(BackendImProtocol.verified(detail(),"302",listOf(course),now))
        assertNull(BackendImProtocol.verified(detail(),"301",listOf(Course("101","999","其他班")),now))
        assertNull(BackendImProtocol.verified(detail(status=2),"301",listOf(course),now))
        assertNull(BackendImProtocol.verified(detail(type=14),"301",listOf(course),now))
    }
    @Test fun endedDetailsAndUnsuccessfulResponsesNeverBecomeAnAlert() {
        val ended=JSONObject(detail());ended.getJSONObject("data").put("endTime",now-1)
        assertNull(BackendImProtocol.verified(ended.toString(),"301",listOf(course),now))
        assertNull(BackendImProtocol.verified("{\"result\":0}","301",listOf(course),now))
    }
    @Test fun queueFetchContainsNoChatMetaOrReadReceiptCursor() {
        val queue=ImPb.Writer().text(2,"synthetic-queue").build()
        val request=ImPb.read(BackendImProtocol.envelope(0,ImPb.Writer().bytes(3,queue).build()))
        val payload=ImPb.read(request.bytes(9)!!)
        assertNull(payload.bytes(1));assertEquals(0L,payload.number(2));assertArrayEquals(queue,payload.bytes(3))
    }
    @Test fun protobufDefaultSuccessIsAcceptedOnlyWhenTheProvisionStatusFieldExists() {
        assertEquals(0L,BackendImProtocol.provisionStatus(ImPb.Writer().bytes(8,byteArrayOf()).build()))
        assertEquals(6L,BackendImProtocol.provisionStatus(ImPb.Writer().bytes(8,ImPb.Writer().number(1,6).build()).build()))
        assertNull(BackendImProtocol.provisionStatus(byteArrayOf()))
    }
}
