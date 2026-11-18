package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoginFlowTest {
    private val page = ChaoxingProtocol.LOGIN_PAGE.toHttpUrl()
    @Test fun normalMobileLoginRedirectIsAllowedButApiAndUntrustedTargetsAreNot() {
        assertEquals("/mlogin", ChaoxingProtocol.loginRedirect(page, "/mlogin?refer=test")?.encodedPath)
        assertNotNull(ChaoxingProtocol.loginRedirect(page, "/login?newversion=true"))
        for (target in listOf("", "http://passport2.chaoxing.com/mlogin", "https://example.com/mlogin",
            "https://passport2.chaoxing.com.example.com/mlogin", "/other-path", "https://user:secret@passport2.chaoxing.com/login"))
            assertNull(target, ChaoxingProtocol.loginRedirect(page, target))
    }
    @Test fun loginRejectionUsesOfficialMsg2ClassificationWithoutEchoingPrivateContent() {
        val error = assertThrows(ApiProblem::class.java) {
            ChaoxingProtocol.checkLoginResult(JSONObject().put("status", false).put("msg2", "账号或密码错误 private-data"))
        }
        assertTrue(error.message!!.contains("学校 / 机构"))
        assertFalse(error.message!!.contains("private-data"))
        val challenge = assertThrows(ApiProblem::class.java) {
            ChaoxingProtocol.checkLoginResult(JSONObject().put("status", false).put("msg2", "请输入验证码"))
        }
        assertTrue(challenge.message!!.contains("验证码"))
    }
    @Test fun twoFactorIsNotMistakenForCompleteLoginAndUnknownFailuresRemainClear() {
        assertThrows(ApiProblem::class.java) {
            ChaoxingProtocol.checkLoginResult(JSONObject().put("status", true).put("containTwoFactorLogin", true))
        }
        ChaoxingProtocol.checkLoginResult(JSONObject().put("status", true))
        val error = assertThrows(ApiProblem::class.java) { ChaoxingProtocol.checkLoginResult(JSONObject().put("status", false)) }
        assertTrue(error.message!!.contains("未接受"))
    }
}
