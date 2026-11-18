package cn.classbell.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.Cookie

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class SecureStorePreservationTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    @Test fun unreadableEncryptedRecordSurvivesReadAndAccountMutationAttempts() {
        val prefs=app.getSharedPreferences("encrypted_session",0)
        val raw="{damaged-encrypted-fixture]"
        prefs.edit().clear().putString("bound.accounts",raw).putString("saved.accounts",raw).commit()
        try {
            assertNull(app.vault.read("bound.accounts"));assertEquals(raw,prefs.getString("bound.accounts",null))
            assertThrows(Exception::class.java){app.vault.readStrict("bound.accounts")}
            val cookies=listOf(Cookie.Builder().name("_uid").value("901").domain("chaoxing.com").path("/").secure().build())
            for(store in listOf(app.boundAccounts,app.savedAccounts)) {
                assertTrue(store.list().isEmpty())
                assertThrows(ApiProblem::class.java){store.bind("合成账号",cookies,emptyList())}
                assertThrows(ApiProblem::class.java){store.unbind("any")}
            }
            assertEquals(raw,prefs.getString("bound.accounts",null));assertEquals(raw,prefs.getString("saved.accounts",null))
        }finally{prefs.edit().clear().commit()}
    }
    @Test fun missingRecordsRemainAbsentAndExplicitRemovalStillWorks() {
        val prefs=app.getSharedPreferences("encrypted_session",0);prefs.edit().clear().commit()
        assertNull(app.vault.readStrict("missing"));assertTrue(prefs.all.isEmpty())
        prefs.edit().putString("damaged","{bad").commit();app.vault.remove("damaged")
        assertFalse(prefs.contains("damaged"))
    }
}
