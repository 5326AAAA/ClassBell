package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class FailureMessagesTest {
    @Test fun connectionAndTimeoutProblemsAreExplainedSeparately() {
        assertTrue(FailureMessages.describe(java.net.UnknownHostException()).contains("DNS"))
        assertTrue(FailureMessages.describe(java.net.SocketTimeoutException()).contains("超时"))
        assertTrue(FailureMessages.describe(javax.net.ssl.SSLHandshakeException("private detail")).contains("安全连接"))
    }
    @Test fun unexpectedExceptionsDoNotExposeRawPrivateDetails() {
        val result = FailureMessages.describe(IllegalStateException("private test credential"))
        assertFalse(result.contains("private test credential"))
        assertTrue(result.contains("不需要通知权限"))
    }
}
