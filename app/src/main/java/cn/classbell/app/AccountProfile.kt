package cn.classbell.app

import org.json.JSONObject
import java.text.Normalizer
import java.security.MessageDigest

data class AccountProfile(val uid:String,val name:String) {
    fun json()=JSONObject().put("uid",uid).put("name",name)
    companion object {
        fun parse(body:String,expected:String):AccountProfile {
            val user=ApiParser.json(body).optJSONObject("msg") ?: throw LoginRequired()
            if(expected.isBlank() || expected !in setOf(user.optString("uid"),user.optString("puid")))throw LoginRequired()
            val name=user.optString("name").trim().take(100)
            if(name.isBlank())throw ApiProblem("未能核对账号资料，请使用官方网页登录。")
            AccountAccess.check(name)
            return AccountProfile(expected,name)
        }
    }
}

internal object AccountAccess {
    private const val fingerprint="5e9093e09c2fe93cb29d557763701fa81e015dd88f03e267757b91f33a1d8e8b"
    fun check(value:String,deny:Set<String> = setOf(fingerprint)) {
        val clean=Normalizer.normalize(value,Normalizer.Form.NFKC).replace(Regex("[\\s\\p{Cf}]+"),"")
        val hash=MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        if(hash in deny)throw ApiProblem("此账号暂时无法使用本应用。")
    }
}
