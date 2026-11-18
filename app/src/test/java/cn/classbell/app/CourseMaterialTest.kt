package cn.classbell.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CourseMaterialTest {
    private val id = "a".repeat(32)

    @Test fun previewMetadataKeepsOriginalAndPdfFromOfficialFileHosts() {
        val status = "https://mooc2-ans.chaoxing.com/ananas/status/$id?flag=normal&ro=0".toHttpUrl()
        assertEquals(id, CourseMaterial.previewId(status))
        val body = JSONObject().put("status", "success").put("objectid", id)
            .put("download", "http://d0.cldisk.com/download/$id")
            .put("pdf", "https://s3.cldisk.com/sv-w7/doc/synthetic.pdf")
            .put("filename", "合成试卷.docx")
        val material = checkNotNull(CourseMaterial.parse(id, body.toString()))
        assertEquals("https://d0.cldisk.com/download/$id", material.original.toString())
        assertEquals("合成试卷.pdf", material.pdfName)
        assertEquals("s3.cldisk.com", material.pdf?.host)
    }

    @Test fun unsupportedFileHostAndMismatchedMetadataAreExcluded() {
        assertNull(CourseMaterial.previewId("https://example.com/ananas/status/$id".toHttpUrl()))
        val body = JSONObject().put("status", "success").put("objectid", id)
            .put("download", "https://d0.cldisk.com.example.com/download/$id")
        assertNull(CourseMaterial.parse(id, body.toString()))
        body.put("pdf", "https://s3.cldisk.com/sv-w7/doc/synthetic.pdf")
        assertNull(CourseMaterial.parse("b".repeat(32), body.toString()))
        assertNull(checkNotNull(CourseMaterial.parse(id, body.toString())).original)
    }

    @Test fun previewReusesWebContextAndKeepsCrossSiteRefererAtOrigin() {
        val url = "https://mooc2-ans.chaoxing.com/ananas/status/$id".toHttpUrl()
        val referer = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?fixture=1"
        val request = CoursePreviewRequest.from(url, mapOf("referer" to referer, "user-agent" to "Synthetic WebView",
            "Authorization" to "synthetic credential"), null, "Default WebView")
        assertEquals(referer, request.headers["Referer"])
        assertEquals("Synthetic WebView", request.headers["User-Agent"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals("https://mooc2-ans.chaoxing.com/", request.origin)

        val fallback = CoursePreviewRequest.from(url, emptyMap(),
            "https://mooc1-api.chaoxing.com/preview?fixture=1", "Default WebView")
        assertEquals("https://mooc1-api.chaoxing.com/", fallback.headers["Referer"])
    }
}
