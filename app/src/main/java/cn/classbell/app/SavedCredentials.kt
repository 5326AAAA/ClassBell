package cn.classbell.app

import org.json.JSONObject
import java.io.File

/** Per-account encrypted credentials, only used to prefill the login form. No export API. */
class SavedCredentials(private val vault: SecureStore) {
    data class Value(val account: String, val password: String)
    private fun key(identity: String): String {
        require(Regex("[a-f0-9]{64}").matches(identity))
        return "credentials.$identity"
    }
    @Synchronized fun read(identity: String): Value? {
        if(identity.isBlank())return null
        return vault.read(key(identity))?.let { raw ->
            val value=JSONObject(raw)
            Value(value.getString("account"),value.getString("password"))
        }
    }
    @Synchronized fun save(identity: String, account: String, password: String, remember: Boolean) {
        if(!remember){remove(identity);return}
        require(account.isNotBlank() && password.isNotEmpty())
        vault.write(key(identity),JSONObject().put("account",account).put("password",password).toString())
        DiagnosticLog.event("Credentials", "saved account=${DiagnosticLog.reference(identity)} encrypted=true")
    }
    @Synchronized fun remove(identity: String) {
        if(identity.isBlank())return
        vault.remove(key(identity))
        DiagnosticLog.event("Credentials", "removed account=${DiagnosticLog.reference(identity)}")
    }
    fun migrate(app: BellApp) {
        if(!app.prefs.getBoolean("credentials.migrated.v1",false)) {
            val profile=JSONObject(vault.read("profile") ?: "{}")
            val identity=app.prefs.getString("identity","").orEmpty()
            if(identity.isNotBlank() && profile.optBoolean("remember") && profile.optString("account").isNotBlank() && profile.optString("password").isNotEmpty())
                save(identity,profile.getString("account"),profile.getString("password"),true)
            if(!profile.optBoolean("remember"))vault.write("profile",profile.put("account","").put("password","").toString())
            vault.remove("debug.export.credentials.v1")
            check(app.prefs.edit().putBoolean("credentials.migrated.v1",true).commit())
            DiagnosticLog.event("Credentials", "saved_login_migration_complete obsolete_archive_removed=true")
        }
        // Remove only this app's obsolete export cache, never the user's saved documents.
        val dir=File(app.cacheDir,"debug-account-exports")
        dir.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile==dir.canonicalFile }?.forEach { check(it.delete()) }
    }
}
