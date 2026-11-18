package cn.classbell.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

interface HomeworkSource {
    fun fetch(courses: List<Course>): HomeworkSnapshot
    fun fetch(courses:List<Course>,previous:HomeworkSnapshot?):HomeworkSnapshot=fetch(courses)
    fun cancel()
}

class HomeworkApi(private val cookies:SessionCookies,transport:OkHttpClient?=null,private val clock:()->Long=System::currentTimeMillis) : HomeworkSource {
    private val client = (transport?.newBuilder()?.eventListenerFactory(DiagnosticLog.httpFactory("HomeworkApi")) ?: OkHttpClient.Builder().eventListenerFactory(DiagnosticLog.httpFactory("HomeworkApi"))).followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    @Volatile private var generation=0
    override fun cancel(){generation++;client.dispatcher.cancelAll()}
    override fun fetch(courses:List<Course>)=fetch(courses,null)
    override fun fetch(courses:List<Course>,previous:HomeworkSnapshot?):HomeworkSnapshot = DiagnosticLog.operation("HomeworkScan", "account=${DiagnosticLog.reference(cookies.userId().orEmpty())} courses=${courses.size} previous=${previous?.items?.size ?: 0}") {
        fetchLogged(courses, previous)
    }
    private fun fetchLogged(courses:List<Course>,previous:HomeworkSnapshot?):HomeworkSnapshot {
        val token=generation
        fun guard(){if(token!=generation || Thread.currentThread().isInterrupted)throw CancellationException()}
        // Snapshot the session for this refresh so account changes cannot change its request cookies.
        val saved=cookies.snapshot().toMutableList()
        val jar=object:CookieJar {
            override fun loadForRequest(url:HttpUrl)=saved.filter{it.expiresAt>System.currentTimeMillis() && it.matches(url)}
            override fun saveFromResponse(url:HttpUrl,values:List<Cookie>){for(value in values.filter{it.domain=="chaoxing.com" || it.domain.endsWith(".chaoxing.com")}){saved.removeAll{it.name==value.name && it.domain==value.domain && it.path==value.path};saved.add(value)}}
        }
        val session=client.newBuilder().cookieJar(jar).build()
        val now=clock()
        val html=read(session,HomeworkParser.LIST_URL,false,::guard)
        val list=HomeworkParser.parse(html,courses,now)
        DiagnosticLog.event("HomeworkScan", "list_parsed chars=${html.length} items=${list.items.size} skipped_rows=${list.skippedRows}")
        for(course in courses) DiagnosticLog.event("HomeworkCourse", "course=${DiagnosticLog.label(course.name)} course_ref=${DiagnosticLog.reference(course.key)} returned_items=${list.items.count{it.courseKey==course.key}} source=aggregate_list absence_is_not_verified_empty=true")
        val items=list.items.toMutableList();val old=previous?.items.orEmpty().associateBy{it.key}
        // Rotate unresolved items ahead of previously resolved metadata on subsequent manual refreshes.
        items.filter{it.activityTask}.forEach{DiagnosticLog.event("HomeworkDeadline", "${DiagnosticLog.homework(it)} detail_skipped reason=activity_task_open_on_user_action")}
        val targets=items.indices.filter{!items[it].peerReview && !items[it].activityTask && items[it].dueAt==null && items[it].state(now) in setOf(HomeworkState.PENDING,HomeworkState.EXPIRED)}
            .sortedWith(compareBy<Int>{if(items[it].state(now)==HomeworkState.PENDING)0 else 1}.thenBy{old[items[it].key]?.deadlineCheckedAt ?: 0L})
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
        var attempted=0
        DiagnosticLog.event("HomeworkDeadline", "candidates=${targets.size} maximum=40 budget_seconds=45")
        for(index in targets.take(40)) {
            guard();if(System.nanoTime()>=until){DiagnosticLog.event("HomeworkDeadline","budget_exhausted remaining_details=true", "WARN");break}
            items[index]=items[index].copy(deadlineCheckedAt=now)
            attempted++
            try {
                val item=items[index]
                val due=DiagnosticLog.operation("HomeworkDetail", DiagnosticLog.homework(item)) {
                    HomeworkDeadline.read(read(session,item.url,true,::guard),now)
                }
                if(due!=null)items[index]=item.copy(dueAt=due.at,sourceTime=due.text)
                DiagnosticLog.event("HomeworkDeadline", "${DiagnosticLog.homework(item)} deadline_found=${due!=null} result=${due?.let{HomeworkDeadline.format(it.at)} ?: "not_present_or_unrecognized"}")
            }catch (e: CancellationException) { DiagnosticLog.error("HomeworkApi", e);throw e}
            catch (e: LoginRequired) { DiagnosticLog.error("HomeworkApi", e);throw e}
            catch (diagnosticError: java.io.IOException) { DiagnosticLog.error("HomeworkApi", diagnosticError);DiagnosticLog.event("HomeworkDeadline", "network_failure remaining_details_skipped=true item=${DiagnosticLog.reference(items[index].key)}", "WARN");guard();break}
            catch (diagnosticError: ApiProblem) { DiagnosticLog.error("HomeworkApi", diagnosticError);DiagnosticLog.event("HomeworkDeadline", "detail_rejected continue_next=true item=${DiagnosticLog.reference(items[index].key)}", "WARN");guard()}
        }
        guard()
        DiagnosticLog.event("HomeworkScan", "summary items=${items.size} deadline_candidates=${targets.size} detail_limit=40 deadline_missing=${items.count{it.dueAt==null}}")
        for(index in targets.drop(attempted)) DiagnosticLog.event("HomeworkDeadline", "${DiagnosticLog.homework(items[index])} not_requested limit_or_earlier_failure=true", "WARN")
        val processed=items.map{item->
            val prior=old[item.key]
            if(item.dueAt==null && item.state(now) in setOf(HomeworkState.PENDING,HomeworkState.EXPIRED) && prior?.dueAt!=null) {
                DiagnosticLog.event("HomeworkDeadline", "${DiagnosticLog.homework(item)} old_deadline_retained=true", "WARN")
                item.copy(dueAt=prior.dueAt,sourceTime="上次读取："+prior.sourceTime.removePrefix("上次读取："),fetchedAt=prior.fetchedAt,deadlineCheckedAt=item.deadlineCheckedAt ?: prior.deadlineCheckedAt)
            }
            else item.copy(deadlineCheckedAt=item.deadlineCheckedAt ?: prior?.deadlineCheckedAt)
        }
        val currentKeys=processed.map{it.key}.toSet()
        val retained=if(list.skippedRows>0)old.values.filter{it.key !in currentKeys} else emptyList()
        if(processed.size+retained.size>3000)throw ApiProblem("合并作业记录过多，已保留上次结果。")
        retained.forEach{DiagnosticLog.event("HomeworkCache", "${DiagnosticLog.homework(it)} retained_unverified=true reason=partial_list", "WARN")}
        DiagnosticLog.event("HomeworkScan", "result fresh=${processed.size} skipped=${list.skippedRows} retained_unverified=${retained.size} complete=${list.skippedRows==0}")
        return list.copy(items=processed+retained,retainedKeys=retained.map{it.key}.toSet())
    }
    private fun read(session:OkHttpClient,value:String,detail:Boolean,guard:()->Unit):String {
        var target=value.toHttpUrlOrNull() ?: throw ApiProblem("作业地址无法识别。")
        val transport=if(detail)session.newBuilder().callTimeout(10,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).build() else session
        repeat(5) { redirect ->
            guard()
            DiagnosticLog.event("HomeworkRead", "kind=${if(detail)"detail" else "list"} redirect_step=$redirect endpoint=${DiagnosticLog.endpoint(target.toString())}")
            if(detail && !HomeworkReadPolicy.allowed(target.toString()))throw ApiProblem("此作业详情需要在学习通中查看。")
            val request=Request.Builder().url(target).get().header("User-Agent","Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .apply{if(detail)header("Referer",HomeworkParser.LIST_URL)}.build()
            transport.newCall(request).execute().use{response->
                guard()
                if(response.code==401)throw LoginRequired()
                if(response.code in 300..399) {
                    val location=response.header("Location")?.let(target::resolve) ?: throw ApiProblem("作业详情发生不支持的跳转。")
                    val next=if(location.scheme=="http" && location.port==80)location.newBuilder().scheme("https").port(443).build() else location
                    if(next.host=="passport2.chaoxing.com" || next.encodedPath.contains("login",true))throw LoginRequired()
                    if(!detail || !HomeworkReadPolicy.allowed(next.toString()))throw ApiProblem("作业详情需要在学习通中核对。")
                    target=next;return@repeat
                }
                if(response.code==429)throw java.io.IOException("作业读取暂时受限")
                if(!response.isSuccessful)throw ApiProblem("作业暂时无法读取（HTTP ${response.code}），已保留已知信息。")
                val body=response.body ?: throw ApiProblem("作业接口返回空响应。")
                val maximum=if(detail)1024L*1024 else 4L*1024*1024
                if(body.source().request(maximum+1))throw ApiProblem("作业响应过大，未继续解析。")
                return body.string()
            }
        }
        throw ApiProblem("作业详情跳转过多，请在学习通中核对。")
    }
}

object HomeworkReadPolicy {
    fun allowed(value:String):Boolean {
        val url=value.toHttpUrlOrNull() ?: return false
        if(!url.isHttps || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment!=null || url.host !in setOf("mooc1-api.chaoxing.com","mooc1.chaoxing.com","mooc1-2.chaoxing.com","mooc2-ans.chaoxing.com"))return false
        val path=url.encodedPath.lowercase()
        return path in setOf("/mooc-ans/android/mtaskmsgspecial","/mooc-ans/mooc2/work/task","/mooc-ans/work/phone/selectworkquestionyipiyue") ||
            Regex("/mooc-ans/work/phone/(?:task|work|preview|prework|view|detail|workdetail|studentworkdetail|taskmsg)(?:[a-z0-9_-]*)").matches(path) &&
            listOf("submit","save","delete","upload","answer","start","dohomework").none(path::contains)
    }
}

interface HomeworkCache {
    fun read(identity: String): HomeworkSnapshot?
    fun write(identity: String, snapshot: HomeworkSnapshot)
}

class EncryptedHomeworkCache(private val vault: SecureStore) : HomeworkCache {
    override fun read(identity: String): HomeworkSnapshot? = diagnosticRunCatching {
        vault.read("homework.$identity")?.let(HomeworkSnapshot::fromJson).also {
            DiagnosticLog.event("HomeworkCache", "read account=${DiagnosticLog.reference(identity)} found=${it!=null} items=${it?.items?.size ?: 0} skipped=${it?.skippedRows ?: 0} retained=${it?.retainedKeys?.size ?: 0}")
        }
    }.getOrNull()
    override fun write(identity: String, snapshot: HomeworkSnapshot) = vault.write("homework.$identity", snapshot.json())
}
