package cn.classbell.app

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BoundAccount(val id:String,val label:String,val revision:String,val cookies:List<Cookie>,val courses:List<Course>) {
    fun json()=JSONObject().put("id",id).put("label",label).put("revision",revision)
        .put("cookies",JSONArray().also{a->cookies.forEach{a.put(JSONObject().put("origin","https://${it.domain}/").put("value",it.toString()))}})
        .put("courses",JSONArray().also{a->courses.forEach{a.put(it.json())}})
}

/** Session list in the Keystore-backed vault. Optional saved passwords are managed by SavedCredentials. */
class BoundAccounts(private val read:()->String?,private val write:(String)->Unit) {
    companion object { const val MAX_ACCOUNTS=10 }
    @Synchronized fun list():List<BoundAccount> = diagnosticRunCatching { load() }.getOrDefault(emptyList())
    private fun load():List<BoundAccount> {
        val array=JSONArray(read() ?: "[]");require(array.length()<=MAX_ACCOUNTS)
        return (0 until array.length()).map{ i->
            val item=array.getJSONObject(i);val raw=item.getJSONArray("cookies");require(raw.length()<=200)
            val cookies=(0 until raw.length()).mapNotNull{j->raw.getJSONObject(j).let{Cookie.parse(it.getString("origin").toHttpUrl(),it.getString("value"))}}
                .filter{it.domain=="chaoxing.com" || it.domain.endsWith(".chaoxing.com")}
            val courses=item.getJSONArray("courses");require(courses.length()<=3000)
            BoundAccount(item.getString("id"),item.getString("label"),item.getString("revision"),cookies,
                (0 until courses.length()).map{j->Course.fromJson(courses.getJSONObject(j))})
        }
    }
    private fun loadForEdit():List<BoundAccount> = try{load()}catch(e:Exception){DiagnosticLog.error("AccountStorage",e);throw ApiProblem("账号存储未能完整读取，已保留原数据。请导出日志后再处理。")}
    @Synchronized fun bind(label:String,cookies:List<Cookie>,courses:List<Course>):BoundAccount {
        val validCookies=cookies.filter{(it.domain=="chaoxing.com" || it.domain.endsWith(".chaoxing.com")) && it.expiresAt>System.currentTimeMillis()}
        if(validCookies.size>200 || courses.size>3000)throw ApiProblem("账号数据超过存储范围，未修改原账号。")
        val uid=validCookies.firstOrNull{it.name in setOf("_uid","UID","uid") && it.expiresAt>System.currentTimeMillis()}?.value ?: throw LoginRequired()
        require(SignProtocol.validId(uid))
        val id=accountKey(uid)
        val old=loadForEdit().filterNot{it.id==id}
        if(old.size>=MAX_ACCOUNTS)throw ApiProblem("最多保存 $MAX_ACCOUNTS 个账号，请先解绑一个。")
        val value=BoundAccount(id,label.trim().take(40).ifBlank{"已授权账号"},UUID.randomUUID().toString(),validCookies.toList(),courses.toList())
        save(old+value);DiagnosticLog.event("Account", "bound account=${DiagnosticLog.reference(id)} courses=${courses.size}");return value
    }
    @Synchronized fun unbind(id:String) { save(loadForEdit().filterNot{it.id==id});DiagnosticLog.event("Account", "unbound account=${DiagnosticLog.reference(id)}") }
    fun current(account:BoundAccount)=list().any{it.id==account.id && it.revision==account.revision}
    private fun save(values:List<BoundAccount>)=write(JSONArray().also{a->values.forEach{a.put(it.json())}}.toString())
}
