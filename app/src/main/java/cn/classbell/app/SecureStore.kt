package cn.classbell.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and cookies are encrypted with a non-exportable Android Keystore key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)
    private val alias = "classbell.session.v1"
    private fun key(create:Boolean=true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if(!create)throw java.security.KeyStoreException("本机解密密钥不存在")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun read(name:String):String? = try { readStrict(name) }
    catch(error:Exception) {DiagnosticLog.error("SecureStore",error);null}

    /** Distinguish absent data from unreadable data for read-modify-write account collections. */
    @Synchronized fun readStrict(name:String):String? {
        val value=prefs.getString(name,null) ?: return null
        val record=JSONObject(value)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(create=false),GCMParameterSpec(128,Base64.decode(record.getString("iv"),Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(record.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8)
    }

    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val record = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        check(prefs.edit().putString(name, record.toString()).commit()) { "无法保存本机登录信息" }
    }

    @Synchronized fun clear() { check(prefs.edit().clear().commit()) { "无法清除本机登录信息" } }
    @Synchronized fun remove(name:String) {check(prefs.edit().remove(name).commit()) {"无法清除本机信息"}}
}
