package cn.classbell.app

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Distinguish an empty result from a search that could not check every course. */
internal object QrCourseLookup {
    fun find(
        activityId: String,
        courses: List<Course>,
        guard: () -> Unit,
        activities: (Course) -> List<SignActivity>,
        onCourseFailure: (Exception) -> Unit
    ): SignActivity {
        guard()
        if (courses.isEmpty()) throw ApiProblem("此账号还没有本地课程。请先刷新课程，再核对二维码。")
        val limit = minOf(3, courses.size)
        val pool = Executors.newFixedThreadPool(limit)
        val finished = ExecutorCompletionService<Result<List<SignActivity>>>(pool)
        val pending = mutableSetOf<Future<Result<List<SignActivity>>>>()
        val remaining = courses.iterator()
        var failures = 0
        fun enqueue() {
            if (!remaining.hasNext()) return
            val course = remaining.next()
            pending += finished.submit(Callable {
                guard()
                try { Result.success(activities(course)) }
                catch (e: CancellationException) { throw e }
                catch (e: LoginRequired) { throw e }
                catch (e: Exception) { guard(); Result.failure(e) }
            })
        }
        try {
            repeat(limit) { enqueue() }
            while (pending.isNotEmpty()) {
                guard()
                val completed = finished.poll(150, TimeUnit.MILLISECONDS) ?: continue
                pending.remove(completed)
                val perCourse = try { completed.get() }
                catch (e: ExecutionException) { throw (e.cause ?: e) }
                guard()
                perCourse.fold(onSuccess = { items ->
                    items.firstOrNull { it.id == activityId && it.kind == ReminderKind.SIGN_IN }?.let { return it }
                }, onFailure = { error ->
                    failures++
                    onCourseFailure(error as Exception)
                })
                enqueue()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Course lookup cancelled")
        } finally {
            pending.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
        guard()
        throw ApiProblem(when (failures) {
            0 -> "此账号的课程中未找到进行中的对应签到。请更新课程，或从对应课程进入后扫码。"
            courses.size -> "${courses.size} 门课程的活动查询均失败。请检查网络或稍后重试。"
            else -> "已查询 ${courses.size - failures} 门课程，另有 $failures 门查询失败，暂不能确定该活动是否存在。请重试。"
        })
    }
}
