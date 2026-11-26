package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppVersion(val major:Int,val minor:Int,val patch:Int):Comparable<AppVersion> {
    override fun compareTo(other:AppVersion)=compareValuesBy(this,other,AppVersion::major,AppVersion::minor,AppVersion::patch)
    companion object {
        fun parse(value:String):AppVersion? {
            val match=Regex("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})").matchEntire(value) ?: return null
            return AppVersion(match.groupValues[1].toInt(),match.groupValues[2].toInt(),match.groupValues[3].toInt())
        }
    }
}
data class AppRelease(val version:AppVersion,val tag:String,val notes:String,val page:String,val apk:ReleaseApk?)
data class ReleaseApk(val name:String,val size:Long,val url:String,val digest:String?)

/** A public, credential-free client, separate from every learning account's session. */
class AppUpdates {
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
        .callTimeout(20,TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    fun latest():AppRelease {
        val request=Request.Builder().url(ENDPOINT).header("Accept","application/vnd.github+json")
            .header("User-Agent","ClassBell/${BuildConfig.VERSION_NAME}").get().build()
        client.newCall(request).execute().use{response->
            if(response.code in setOf(403,429))throw ApiProblem("GitHub 请求受限，请稍后再检查。")
            if(!response.isSuccessful)throw ApiProblem("更新检查未完成（HTTP ${response.code}），请稍后重试。")
            val body=response.body ?: throw ApiProblem("GitHub 返回空响应。")
            if(body.source().request(MAX_BODY+1))throw ApiProblem("更新说明过大，已停止读取。")
            return parse(body.string())
        }
    }
    fun cancel(){client.dispatcher.cancelAll()}
    companion object {
        const val ENDPOINT="https://api.github.com/repos/5326AAAA/ClassBell/releases/latest"
        private const val MAX_BODY=1024L*1024
        private fun ownUrl(value:String,path:String):Boolean {
            val url=value.toHttpUrlOrNull() ?: return false
            return url.isHttps && url.host=="github.com" && url.port==443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.query==null && url.fragment==null && url.encodedPath==path
        }
        fun parse(body:String):AppRelease {
            if(body.toByteArray(Charsets.UTF_8).size>MAX_BODY)throw ApiProblem("更新响应过大。")
            val root=JSONObject(body)
            if(root.optBoolean("draft") || root.optBoolean("prerelease"))throw ApiProblem("当前返回的是测试版，请前往 GitHub 查看。")
            val tag=root.optString("tag_name")
            val version=AppVersion.parse(tag) ?: throw ApiProblem("发布版本号无法识别。")
            val page=root.optString("html_url")
            if(!ownUrl(page,"/5326AAAA/ClassBell/releases/tag/$tag"))throw ApiProblem("发布页面地址不匹配。")
            val assets=root.optJSONArray("assets")
            val choices=(0 until minOf(assets?.length() ?: 0,100)).mapNotNull{i->
                val row=assets?.optJSONObject(i) ?: return@mapNotNull null
                val name=row.optString("name");val size=row.optLong("size",0);val url=row.optString("browser_download_url")
                if(!Regex("ClassBell[-A-Za-z0-9_.]*\\.apk",RegexOption.IGNORE_CASE).matches(name) || size !in 1..256L*1024*1024 ||
                    !ownUrl(url,"/5326AAAA/ClassBell/releases/download/$tag/$name"))return@mapNotNull null
                ReleaseApk(name,size,url,row.optString("digest").takeIf{Regex("sha256:[a-f0-9]{64}").matches(it)})
            }
            // Prefer a universal package; ABI-specific packages remain available on the release page.
            val apk=choices.firstOrNull{listOf("arm64","armeabi","x86").none{abi->it.name.contains(abi,true)}}
            return AppRelease(version,tag,root.optString("body").take(12_000),page,apk)
        }
    }
}
