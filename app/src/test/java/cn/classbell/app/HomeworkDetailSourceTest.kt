package cn.classbell.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class HomeworkDetailSourceTest {
    private val host = "https://mooc1-api.chaoxing.com"
    private val entry = "$host/mooc-ans/android/mtaskmsgspecial?courseId=101&clazzId=201&taskrefId=901&enc=signed%2Fvalue"
    private val question = "$host/mooc-ans/work/phone/selectWorkQuestion?workId=901&enc=keep%2Fthis%3D"
    private val html = "<html><title>作业题目</title><div class=question>合成题目</div></html>"

    private fun item(url: String = entry) = HomeworkItem(
        "901", "合成作业", "合成课程", "101:201", "未提交", "", null, 1L, false, url
    )

    private fun reply(request: Request, code: Int = 200, body: String = html,
                      location: String? = null, cookie: String? = null, type: String = "text/html"): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
            .apply {
                location?.let { header("Location", it) }
                cookie?.let { header("Set-Cookie", it) }
            }.body(body.toResponseBody(type.toMediaType())).build()

    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("GET", chain.request().method)
        block(chain.request())
    }.build()

    private fun close(source: HomeworkDetailSource, transport: OkHttpClient) {
        source.cancel()
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdown()
    }

    @Test fun immutableSnapshotUsesMatchingCookiesAndDoesNotBorrowInjectedCookieJar() {
        val cookies = mutableListOf(
            Cookie.Builder().name("_uid").value("first").domain("chaoxing.com").path("/").secure().build(),
            Cookie.Builder().name("foreign").value("no").hostOnlyDomain("other.invalid").path("/").build(),
            Cookie.Builder().name("narrow").value("no").domain("chaoxing.com").path("/elsewhere").build()
        )
        val requests = mutableListOf<Request>()
        val transport = client { request ->
            requests += request
            if (requests.size == 1) reply(request, 302, location = question,
                cookie = "_uid=server-replacement; Domain=.chaoxing.com; Path=/; Secure")
            else reply(request)
        }.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = error("must not read live jar")
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("must not save session")
        }).build()
        val source = HomeworkDetailSource(cookies, {}, transport)
        cookies.clear()
        cookies += Cookie.Builder().name("_uid").value("second").domain("chaoxing.com").path("/").build()
        try {
            val page = source.load(item())
            assertEquals(question, page.url)
            assertEquals(html, page.html)
            assertEquals(listOf("_uid=first", "_uid=first"), requests.map { it.header("Cookie") })
            assertEquals(entry, requests[0].url.toString())
            assertEquals(HomeworkParser.LIST_URL, requests[0].header("Referer"))
            assertEquals(entry, requests[1].header("Referer"))
        } finally { close(source, transport) }
    }

    @Test fun exactQuestionReadPathsAreSeparateFromBackgroundDeadlinePolicy() {
        assertFalse(HomeworkReadPolicy.allowed(question))
        assertTrue(HomeworkDetailPolicy.allowed(question))
        assertTrue(HomeworkDetailPolicy.allowed("$host/mooc-ans/work/phone/selectWorkQuestionYiPiYue?workId=901"))
        assertTrue(HomeworkDetailPolicy.allowed("$host/mooc-ans/work/phone/task-work?workId=901"))
    }

    @Test fun approvedMoocRedirectsKeepCookiesScopedToTheirExactDomainRules() {
        for (destination in listOf("mooc1.chaoxing.com", "mooc1-2.chaoxing.com", "mooc2-ans.chaoxing.com")) {
            val requests = mutableListOf<Request>()
            val next = question.replace("mooc1-api.chaoxing.com", destination)
            val cookies = listOf(
                Cookie.Builder().name("_uid").value("shared").domain("chaoxing.com").path("/").secure().build(),
                Cookie.Builder().name("only_api").value("first").hostOnlyDomain("mooc1-api.chaoxing.com").path("/").secure().build(),
                Cookie.Builder().name("only_destination").value("second").hostOnlyDomain(destination).path("/").secure().build(),
                Cookie.Builder().name("outside").value("no").hostOnlyDomain("other.invalid").path("/").secure().build()
            )
            val transport = client { request ->
                requests += request
                if (requests.size == 1) reply(request, 302, location = next) else reply(request)
            }
            val source = HomeworkDetailSource(cookies, {}, transport)
            try {
                assertEquals(next, source.load(item()).url)
                assertEquals(2, requests.size)
                assertEquals(setOf("_uid=shared", "only_api=first"), requests.first().header("Cookie")!!.split("; ").toSet())
                assertEquals(setOf("_uid=shared", "only_destination=second"), requests.last().header("Cookie")!!.split("; ").toSet())
                assertEquals("$host/", requests.last().header("Referer"))
            } finally { close(source, transport) }
        }
    }

    @Test fun unsafeInitialRoutesAreRejectedBeforeAnyRequest() {
        var requests = 0
        val transport = client { requests++; reply(it) }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        val unsafe = listOf(
            "http://mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestion",
            "https://mooc1-api.chaoxing.com:444/mooc-ans/work/phone/selectWorkQuestion",
            "https://mooc1-api.chaoxing.com.other.invalid/mooc-ans/work/phone/selectWorkQuestion",
            "https://person:secret@mooc1-api.chaoxing.com/mooc-ans/work/phone/selectWorkQuestion",
            "$question#fragment", "$question&action=submit", "$question&save=1",
            "$host/mooc-ans/work/phone/selectWorkQuestionSave",
            "$host/mooc-ans/work/phone/task-submit",
            "$host/mooc-ans/work/phone/doHomeWork?workId=901",
            "$host/mooc-ans/work/phone/start?workId=901",
            "$host/mooc-ans/work/phone/answer?workId=901",
            "$host/mooc-ans/work/phone/save?workId=901",
            "$host/mooc-ans/work/phone/submit?workId=901",
            "$host/mooc-ans/work/phone/upload?workId=901",
            "$host/mooc-ans/work/phone/delete?workId=901"
        )
        try {
            unsafe.forEach { assertThrows(it, ApiProblem::class.java) { source.load(item(it)) } }
            assertThrows(ApiProblem::class.java) { source.load(item().copy(activityTask = true)) }
            assertThrows(ApiProblem::class.java) { source.load(item().copy(peerReview = true)) }
            assertEquals(0, requests)
        } finally { close(source, transport) }
    }

    @Test fun previewAnchorFollowsOnlyThePageSuppliedQuestionUrlAndPreservesItsSignature() {
        val requests = mutableListOf<Request>()
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        val next = question.replace("mooc1-api.chaoxing.com", "mooc1.chaoxing.com")
        val transport = client { request ->
            requests += request
            when (requests.size) {
                1 -> reply(request, 302, location = preview)
                2 -> reply(request, body = "<a href='${next.replace("&", "&amp;")}'>读取题目</a><img src='/not-requested.png'>")
                else -> reply(request)
            }
        }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try {
            assertEquals(next, source.load(item()).url)
            assertEquals(listOf(entry, preview, next), requests.map { it.url.toString() })
            assertEquals("$host/", requests.last().header("Referer"))
        } finally { close(source, transport) }
    }

    @Test fun literalPreviewNavigationIsReadWithoutEvaluatingJavascriptOrInventingParameters() {
        val requests = mutableListOf<Request>()
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        val transport = client { request ->
            requests += request
            if (requests.size == 1) reply(request, body = """
                <script>
                function jump(){ processOpenUrl('/mooc-ans/work/phone/selectWorkQuestion?workId=901\u0026enc=page%2Fsignature'); }
                fetch('/mooc-ans/work/phone/save');
                </script>
            """.trimIndent()) else reply(request)
        }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try {
            assertEquals("page/signature", source.load(item(preview)).url.toHttpUrlOrNull()!!.queryParameter("enc"))
            assertEquals(2, requests.size)
            assertEquals("901", requests.last().url.queryParameter("workId"))
        } finally { close(source, transport) }
    }

    @Test fun submittedPreviewOriginAndLiteralContextNavigateToTheExactReadOnlyQuestionPage() {
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        val requests = mutableListOf<Request>()
        val transport = client { request ->
            requests += request
            if(requests.size == 1) reply(request, body = """
                <span onclick="look(this)">查看详情</span>
                <script>_CP_ = "/mooc-ans";
                function look(_this) {
                    $(_this).removeAttr("onclick");
                    var url = location.origin + _CP_ + '/work/phone/selectWorkQuestionYiPiYue?courseId=101&workAnswerId=801&workId=901&enc=page%2Fsignature';
                    processOpenUrl(url, "查看作业", 1);
                }
                function redo() { fetch('/mooc-ans/work/phone/redo'); }
                </script>
            """.trimIndent()) else reply(request)
        }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try {
            val page = source.load(item(preview))
            val next = page.url.toHttpUrlOrNull()!!
            assertEquals("/mooc-ans/work/phone/selectWorkQuestionYiPiYue", next.encodedPath)
            assertEquals("801", next.queryParameter("workAnswerId"))
            assertEquals("page/signature", next.queryParameter("enc"))
            assertEquals(2, requests.size)
        } finally { close(source, transport) }
    }

    @Test fun originContextNavigationDoesNotEvaluateExtraExpressionsOrFollowWriteTargets() {
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        for ((context, expression) in listOf(
            "/mooc-ans" to "'/work/phone/selectWorkQuestion?workId=' + workId",
            "/mooc-ans" to "'/work/phone/doHomeWork?workId=901'",
            "/mooc-ans" to "'/work/phone/selectWorkQuestion?workId=901&action=submit'",
            "/other" to "'/work/phone/selectWorkQuestion?workId=901'"
        )) {
            var count = 0
            val transport = client { request ->
                count++
                reply(request, body = """<script>_CP_ = "$context";
                    function look(){var url = location.origin + _CP_ + $expression; processOpenUrl(url, "查看", 1);}
                    </script>""")
            }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try {
                assertEquals(preview, source.load(item(preview)).url)
                assertEquals(1, count)
            } finally { close(source, transport) }
        }
    }

    @Test fun submittedReportFollowsTheFullPaperLinkWithPageProvidedHiddenAndLiteralValues() {
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        val report = "$host/mooc-ans/work/phone/selectWorkQuestionYiPiYue?workAnswerId=801"
        val full = "<div class='questionLi'><h3 class='mark_name'>完整题干</h3></div>"
        val requests = mutableListOf<Request>()
        val transport = client { request ->
            requests += request
            when(requests.size) {
                1 -> reply(request, body = "<a href='$report'>查看详情</a>")
                2 -> reply(request, body = """
                    <input type=hidden id=times value=0>
                    <span onclick="look(this)">查看详情</span>
                    <script>_CP_ = "/mooc-ans";
                    var times = $("#times").val();var isAccessibility = 0;
                    function _jsBridgeReady(){
                        jsBridge.bind('CLIENT_ACCESSIBILITY_STATUS', function(object){
                            if(object && object.isSpokenActive == 1){isAccessibility = 1;}else{isAccessibility = 0;}
                        });
                    }
                    function look(_this){
                        var url = location.origin + _CP_ + "/work/phone/selectWorkQuestionYiPiYue?workAnswerId=801&isReport=0&times=" + times + "&isAccessibility=" + isAccessibility;
                        processOpenUrl(url, "查看详情", 1);
                    }
                    </script>
                """.trimIndent())
                else -> reply(request, body = full)
            }
        }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try {
            val page = source.load(item(preview))
            assertEquals(full, page.html)
            assertEquals(3, requests.size)
            assertEquals("0", requests.last().url.queryParameter("isReport"))
            assertEquals("0", requests.last().url.queryParameter("times"))
            assertEquals("0", requests.last().url.queryParameter("isAccessibility"))
        } finally { close(source, transport) }
    }

    @Test fun reportConcatenationDoesNotRunFunctionsOrGuessMissingAndConflictingVariables() {
        val report = "$host/mooc-ans/work/phone/selectWorkQuestionYiPiYue?workAnswerId=801"
        for ((declarations, end) in listOf(
            "var times = 0; var times = 1;" to "times",
            "var times = loadSecret();" to "times",
            "var times = 0;" to "times + loadSecret()",
            "" to "unknownValue"
        )) {
            var count = 0
            val transport = client { request ->
                count++
                reply(request, body = """<script>_CP_ = "/mooc-ans";
                    $declarations
                    function look(){var url = location.origin + _CP_ + '/work/phone/selectWorkQuestionYiPiYue?isReport=0&times=' + $end; processOpenUrl(url, "查看", 1);}
                    </script>""")
            }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try {
                assertEquals(report, source.load(item(report)).url)
                assertEquals(1, count)
            } finally { close(source, transport) }
        }
    }

    @Test fun unstartedWorkRequiresAnExplicitBeginAndOnlyOpensTheExactPageSuppliedGet() {
        val preview="$host/mooc-ans/work/phone/task-work?workId=901"
        val requests=mutableListOf<Request>()
        val transport=client{request->
            requests+=request
            if(request.url.encodedPath.endsWith("task-work"))reply(request,body="""
                <input type=button value="开始答题" onclick="jump()">
                <a href='/mooc-ans/work/phone/selectWorkQuestionYiPiYue?workId=901'>查看详情</a>
                <script>_CP_='/mooc-ans';var ios_focus_problem='keyboardDisplayRequiresUserAction=1';
                function jump(){var url=location.origin+_CP_+'/work/phone/doHomeWork?workId=901&enc=page%2Fsignature'+'&'+ios_focus_problem;processOpenUrl(url,'',1);}
                </script>
            """.trimIndent()) else reply(request,body="<div class=singleQuesId><div class=qtContent>题目</div></div>")
        }
        val source=HomeworkDetailSource(emptyList(),{},transport)
        try {
            assertThrows(HomeworkStartRequired::class.java){source.load(item(preview))}
            assertEquals(1,requests.size)
            val page=source.load(item(preview),begin=true)
            assertTrue(page.html.contains("qtContent"))
            assertEquals("page/signature",requests.last().url.queryParameter("enc"))
            assertTrue(requests.last().url.encodedPath.endsWith("doHomeWork"))
            assertEquals("1",requests.last().url.queryParameter("keyboardDisplayRequiresUserAction"))
            assertEquals(1,requests.count{it.url.encodedPath.endsWith("doHomeWork")})
            assertThrows(HomeworkStartRequired::class.java){source.load(item(preview))}
            assertEquals(1,requests.count{it.url.encodedPath.endsWith("doHomeWork")})
        }finally{close(source,transport)}
    }
    @Test fun beginFlagDoesNotFollowAnUnusedStartRouteWithoutTheDisplayedStartControl() {
        val preview="$host/mooc-ans/work/phone/task-work?workId=901"
        var count=0
        val transport=client{request->count++;reply(request,body="<script>processOpenUrl('/mooc-ans/work/phone/doHomeWork?workId=901')</script><p>等待老师允许查看</p>")}
        val source=HomeworkDetailSource(emptyList(),{},transport)
        try {
            assertEquals(preview,source.load(item(preview),begin=true).url)
            assertEquals(1,count)
        }finally{close(source,transport)}
    }
    @Test fun permissionErrorIsReportedAsUnavailableRatherThanAnEmptyAssignment() {
        val transport=client{request->reply(request,body="<title>信息提示</title><h1>信息提示</h1><p>无权限操作！</p>")}
        val source=HomeworkDetailSource(emptyList(),{},transport)
        try {
            val error=assertThrows(ApiProblem::class.java){source.load(item())}
            assertTrue(error.message!!.contains("未允许查看"))
        }finally{close(source,transport)}
    }
    @Test fun missingAmbiguousDynamicOrUnsafePreviewTargetsReturnThePreviewUnchanged() {
        val preview = "$host/mooc-ans/work/phone/task-work?workId=901"
        val pages = listOf(
            "<p>请在学习通开始作业</p>",
            "<script>processOpenUrl(base + workId)</script>",
            "<a href='/mooc-ans/work/phone/selectWorkQuestion?workId=1'>一</a><a href='/mooc-ans/work/phone/selectWorkQuestion?workId=2'>二</a>",
            "<a href='https://other.invalid/mooc-ans/work/phone/selectWorkQuestion'>题目</a>",
            "<a href='/mooc-ans/work/phone/doHomeWork?workId=901'>开始</a>"
        )
        for (page in pages) {
            var count = 0
            val transport = client { count++; reply(it, body = page) }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try {
                assertEquals(HomeworkDetailPage(page, preview), source.load(item(preview)))
                assertEquals(1, count)
            } finally { close(source, transport) }
        }
    }

    @Test fun redirectsCannotLeaveApprovedHostsDowngradeOrEnterAWriteRoute() {
        val destinations = listOf(
            question.replace("https://", "http://"),
            question.replace("mooc1-api.chaoxing.com", "other.invalid"),
            "$host/mooc-ans/work/phone/submit?workId=901",
            "$question&operation=start"
        )
        for (destination in destinations) {
            var count = 0
            val transport = client { count++; reply(it, 302, location = destination) }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try {
                assertThrows(ApiProblem::class.java) { source.load(item()) }
                assertEquals(1, count)
            } finally { close(source, transport) }
        }
    }

    @Test fun redirectsAreBoundedAndLoopsNeverIssueAnotherRequest() {
        for (loop in listOf(false, true)) {
            var count = 0
            val transport = client { request ->
                count++
                reply(request, 302, location = if (loop) entry else "$question&step=$count")
            }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try {
                assertThrows(ApiProblem::class.java) { source.load(item()) }
                assertEquals(if (loop) 1 else 5, count)
            } finally { close(source, transport) }
        }
    }

    @Test fun loginResponsesAreNotReturnedAsQuestionsAndChallengesAreNotBypassed() {
        val pages = listOf(
            401 to "",
            200 to "<title>用户登录</title>",
            200 to "<form><input type=password></form>",
            200 to "<p>请先登录</p>",
            200 to "<div class=questionLi><h3 class=mark_name>缓存题目</h3></div><form action='https://passport2.chaoxing.com/login'><input type=password></form>"
        )
        for ((code, page) in pages) {
            val transport = client { reply(it, code, page) }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try { assertThrows(LoginRequired::class.java) { source.load(item()) } }
            finally { close(source, transport) }
        }
        val transport = client { reply(it, 302, location = "https://passport2.chaoxing.com/login") }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try { assertThrows(LoginRequired::class.java) { source.load(item()) } }
        finally { close(source, transport) }
        val challenge = client { reply(it, body = "<title>安全验证</title><div class=questionLi><h3 class=mark_name>缓存题目</h3></div><form><input name=captcha></form>") }
        val blocked = HomeworkDetailSource(emptyList(), {}, challenge)
        try { assertThrows(ApiProblem::class.java) { blocked.load(item()) } }
        finally { close(blocked, challenge) }
    }

    @Test fun aQuestionMentioningVerificationIsStillReadable() {
        val page = "<title>作业</title><p>什么是验证码？请解释安全验证的用途。</p>"
        val transport = client { reply(it, body = page) }
        val source = HomeworkDetailSource(emptyList(), {}, transport)
        try { assertEquals(page, source.load(item()).html) }
        finally { close(source, transport) }
    }

    @Test fun genuineQuestionStructureAllowsLoginAndCaptchaTopicsIncludingExampleInputs() {
        val pages = listOf(
            "<title>用户登录与验证码</title><div class=questionLi><h3 class=mark_name>解释“请先登录”和“登录已失效”流程。</h3></div>",
            "<title>安全验证</title><div class=questionLi><h3 class=mark_name>验证码如何工作？</h3><form action='/login'><input type=password><input name=captcha></form></div>",
            "<title>登录实验</title><div class=question-stem>请重新登录后出现什么提示？</div>"
        )
        for (page in pages) {
            val transport = client { reply(it, body = page) }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try { assertEquals(page, source.load(item()).html) }
            finally { close(source, transport) }
        }
    }

    @Test fun binaryAndOversizeResponsesAreRejectedIncludingUnknownLengthStreams() {
        for (type in listOf("image/png", "application/pdf", "application/json")) {
            val transport = client { reply(it, type = type) }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try { assertThrows(ApiProblem::class.java) { source.load(item()) } }
            finally { close(source, transport) }
        }
        for (known in listOf(false, true)) {
            val transport = client { request ->
                val bytes = "x".repeat(HomeworkDetailSource.MAX_HTML_BYTES.toInt() + 1)
                val body = if (known) bytes.toResponseBody("text/html".toMediaType()) else object : ResponseBody() {
                    private val buffer = Buffer().writeUtf8(bytes)
                    override fun contentType(): MediaType = "text/html".toMediaType()
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = buffer
                }
                reply(request).newBuilder().body(body).build()
            }
            val source = HomeworkDetailSource(emptyList(), {}, transport)
            try { assertThrows(ApiProblem::class.java) { source.load(item()) } }
            finally { close(source, transport) }
        }
    }

    @Test fun accountGuardRejectsResponsesAndDoesNotFollowTheirRedirect() {
        var current = true
        var count = 0
        val transport = client { request ->
            count++
            current = false
            reply(request, 302, location = question)
        }
        val source = HomeworkDetailSource(emptyList(), { if (!current) throw CancellationException() }, transport)
        try {
            assertThrows(CancellationException::class.java) { source.load(item()) }
            assertEquals(1, count)
        } finally { close(source, transport) }
    }

    @Test fun cancellationCancelsOnlyThisSourceAndDiscardsTheResponse() {
        lateinit var source: HomeworkDetailSource
        val transport = client { request -> source.cancel(); reply(request) }
        val unrelated = transport.newCall(Request.Builder().url(question).build())
        source = HomeworkDetailSource(emptyList(), {}, transport)
        try {
            assertThrows(CancellationException::class.java) { source.load(item()) }
            assertFalse(unrelated.isCanceled())
            assertThrows(CancellationException::class.java) { source.load(item()) }
        } finally { close(source, transport) }
    }

    @Test fun anInvalidGuardPreventsTheFirstNetworkRequest() {
        var count = 0
        val transport = client { count++; reply(it) }
        val source = HomeworkDetailSource(emptyList(), { throw CancellationException() }, transport)
        try {
            assertThrows(CancellationException::class.java) { source.load(item()) }
            assertEquals(0, count)
        } finally { close(source, transport) }
    }
}
