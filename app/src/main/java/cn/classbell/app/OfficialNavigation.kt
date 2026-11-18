package cn.classbell.app

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener

object OfficialPagePolicy {
    private val hosts=setOf("mooc1-api.chaoxing.com","mooc1.chaoxing.com","mooc1-2.chaoxing.com","mooc2-ans.chaoxing.com","mobilelearn.chaoxing.com","passport2.chaoxing.com","sso.chaoxing.com","i.chaoxing.com","pan-yz.chaoxing.com")
    fun allowed(value:String):Boolean {
        val url=value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port==443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
    }
    fun navigation(value:String):String? {
        val url=value.toHttpUrlOrNull() ?: return null
        val secure=if(url.scheme=="http" && url.port==80)url.newBuilder().scheme("https").port(443).build() else url
        return secure.toString().takeIf{allowed(it)}
    }
    fun initial(value:String):Boolean {
        val url=value.toHttpUrlOrNull() ?: return false
        return allowed(value) && (HomeworkParser.safeUrl(value) || SignQr.parse(value)!=null || url.encodedPath in setOf("/mooc-ans/visit/stucoursemiddle","/visit/stucoursemiddle","/newsign/preSign"))
    }
    fun files(values:List<Uri>)=values.distinct().take(10).filter{it.scheme=="content"}
    fun bridgeId(value:String):Int? {
        val id=Regex("^jsbridge://postnotificationwithid-([0-9]{1,7})/?$",RegexOption.IGNORE_CASE).matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
        return id?.takeIf{it in 1..1_000_000}
    }
    fun notification(encoded:String):JSONObject?=diagnosticRunCatching {
        if(encoded.length>65_536)return null
        val decoded=JSONTokener(encoded).nextValue()
        if(decoded is String)JSONObject(decoded) else decoded as? JSONObject
    }.getOrNull()
}
