package cn.classbell.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun payload()=JSONObject().put("tag_name","v1.10.0").put("html_url","https://github.com/5326AAAA/ClassBell/releases/tag/v1.10.0")
        .put("body","更新内容").put("assets",JSONArray().put(JSONObject().put("name","ClassBell-v1.10.0-debug.apk").put("size",12345)
            .put("browser_download_url","https://github.com/5326AAAA/ClassBell/releases/download/v1.10.0/ClassBell-v1.10.0-debug.apk")))
    @Test fun versionsAreNumericAndStrict() {
        assertTrue(AppVersion.parse("v1.10.0")!!>AppVersion.parse("1.9.9")!!)
        for(value in listOf("1.2","1.2.3-beta","01.2.3","1.2.3/evil","-1.2.3","1000000.0.0"))assertNull(value,AppVersion.parse(value))
    }
    @Test fun validPublicReleaseIncludesMatchingApk() {
        val parsed=AppUpdates.parse(payload().toString())
        assertEquals("v1.10.0",parsed.tag);assertEquals(12345L,parsed.apk!!.size)
    }
    @Test fun foreignAndCredentialBearingAndRedirectLikeAssetUrlsAreNotDownloadButtons() {
        val original=payload().getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
        for(url in listOf(original.replace("github.com","evil.example"),original.replace("github.com","github.com.evil.example"),
            original.replace("https://","https://user:password@"),"$original?redirect=evil","$original#fragment",original.replace("https:","http:"),
            original.replace("5326AAAA/ClassBell","elsewhere/ClassBell"),original.replace("v1.10.0/","v1.9.0/"))) {
            val root=payload();root.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url)
            assertNull(url,AppUpdates.parse(root.toString()).apk)
        }
    }
    @Test fun draftPrereleaseAndForeignPageAreRejected() {
        for(key in listOf("draft","prerelease"))assertThrows(ApiProblem::class.java){AppUpdates.parse(payload().put(key,true).toString())}
        assertThrows(ApiProblem::class.java){AppUpdates.parse(payload().put("html_url","https://evil.example/release").toString())}
    }
    @Test fun missingAndAbiOnlyPackagesKeepReleaseReadableWithoutWrongDownload() {
        assertNull(AppUpdates.parse(payload().put("assets",JSONArray()).toString()).apk)
        val root=payload();val a=root.getJSONArray("assets").getJSONObject(0)
        a.put("name","ClassBell-arm64-v8a.apk").put("browser_download_url","https://github.com/5326AAAA/ClassBell/releases/download/v1.10.0/ClassBell-arm64-v8a.apk")
        assertNull(AppUpdates.parse(root.toString()).apk)
    }
}
