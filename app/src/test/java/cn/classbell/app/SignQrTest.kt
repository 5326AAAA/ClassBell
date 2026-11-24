package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class SignQrTest {
    private val sample="https://mobilelearn.chaoxing.com/widget/sign/e?id=1700000000000&c=123456&enc=0123456789abcdef0123456789abcdef&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=id"
    @Test fun widgetRecordAndCodeMayDifferAndThePayloadIsNotRenderedAsText() {
        val value=SignQr.parse(sample)!!
        assertEquals(sample,value.officialUrl);assertEquals("SignQr(redacted)",value.toString())
        assertTrue(OfficialPagePolicy.initial(sample))
    }
    @Test fun malformedOrForeignCodesNeverBecomeAnOpenableOfficialUrl() {
        val invalid=listOf(sample.replace("https:","http:"),sample.replace("mobilelearn.chaoxing.com","mobilelearn.chaoxing.com.example.org"),sample.replace("mobilelearn.chaoxing.com","user@mobilelearn.chaoxing.com"),sample+"&id=1",sample+"&enc=a",sample+"&next=https://example.org",sample+"#fragment",sample.replace("PRIMARY_KEY","OTHER"),sample.replace("/widget/sign/e","/pptSign/stuSignajax"),sample.replace("123456","not-numeric"),sample+"\n",sample.replace("0123456789abcdef0123456789abcdef","short"),"x".repeat(4097))
        invalid.forEach{assertNull(SignQr.parse(it))}
    }
}
