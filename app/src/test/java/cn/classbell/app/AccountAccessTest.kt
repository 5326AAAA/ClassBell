package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test

class AccountAccessTest {
    @Test fun normalizedServerNamesAreCheckedWithoutUsingTheUserEditableLabel() {
        val denied=setOf(accountKey("合成账号"))
        assertThrows(ApiProblem::class.java){AccountAccess.check(" 合成\u200b账 号 ",denied)}
        AccountAccess.check("其他合成账号",denied)
        val error=runCatching{AccountAccess.check("合成账号",denied)}.exceptionOrNull()!!
        assertFalse(error.message.orEmpty().contains("合成账号"))
    }
}
