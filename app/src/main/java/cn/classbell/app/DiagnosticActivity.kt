package cn.classbell.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class DiagnosticActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var state: TextView
    private lateinit var preview: TextView
    private val actions = mutableListOf<Button>()
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = StudyUi.column(this).apply {
            setBackgroundColor(ModernUi.background)
            setPadding(ModernUi.dp(this@DiagnosticActivity, 18), 0, ModernUi.dp(this@DiagnosticActivity, 18), 0)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(ModernUi.dp(this, 18), insets.systemWindowInsetTop, ModernUi.dp(this, 18), insets.systemWindowInsetBottom)
            insets
        }
        root.addView(StudyUi.header(this, "运行日志与故障反馈") { finish() }.first)
        root.addView(StudyUi.text(this, "日志自动记录。出现问题后点“分享日志到微信”，选联系人发送；也可保存 TXT 文件。日志包含课程、作业、活动和资料名称，账号使用匿名标识；不记录密码、Cookie、答题正文、照片或精确坐标。", 13))
        state = StudyUi.text(this, "", 12); root.addView(state)
        fun action(title: String, primary: Boolean = false, block: () -> Unit) {
            StudyUi.button(this, title, primary, block).also { actions += it; root.addView(it) }
        }
        action("分享日志到微信", true) { export(true) }
        action("通过其他应用分享 TXT") { export(false) }
        action("保存 TXT 到手机") {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "ClassBell-log-${System.currentTimeMillis()}.txt"), 204)
            } catch (e: Exception) { DiagnosticLog.error("LogSave", e); state.text = "无法打开文件保存界面，请使用分享或复制。" }
        }
        val row = LinearLayout(this)
        for ((title, block) in listOf<Pair<String, () -> Unit>>(
            "刷新" to { refresh() },
            "复制最近日志" to { load({ DiagnosticLog.snapshot().takeLast(24000) }) { text ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Class Bell 最近日志", text))
                state.text = "已复制最近日志；完整记录请分享 TXT。"
            } },
            "清空" to { ModernUi.dialog(this).setTitle("清空运行日志？").setMessage("将删除本机诊断记录；已导出或发出的文件不会被删除。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> load({ DiagnosticLog.clear(); DiagnosticLog.snapshot() }) { show(it) } }.show() }
        )) {
            val button = StudyUi.button(this, title, action = block)
            actions += button; row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row)
        preview = StudyUi.text(this, "", 11).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(root) }); refresh()
    }
    private fun show(text: String) {
        state.text = "日志自动轮换，最多约 8 MiB。下方显示最近 24,000 字符，分享包含完整保留记录。"
        preview.text = text.takeLast(24000)
    }
    private fun refresh() = load({ DiagnosticLog.snapshot() }, ::show)
    private fun <T> load(block: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; actions.forEach { it.isEnabled = false }; state.text = "正在处理日志…"
        worker.execute {
            val result = runCatching(block)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; actions.forEach { it.isEnabled = true }
                try { done(result.getOrThrow()) }
                catch (error: Exception) { DiagnosticLog.error("LogExport", error); state.text = "日志操作失败：${error.javaClass.simpleName}，请重试并检查存储空间。" }
            }
        }
    }
    private fun export(wechat: Boolean) {
        DiagnosticLog.event("LogExport", "requested destination=${if (wechat) "wechat" else "chooser"}")
        load({ LogSharing.createFile(this, DiagnosticLog.snapshot()) }) { file ->
            val send = LogSharing.intent(this, file)
            try {
                if (wechat) startActivity(Intent(send).setPackage("com.tencent.mm"))
                else startActivity(Intent.createChooser(send, "分享日志，或选择文件应用保存"))
                state.text = "日志已导出；请在分享界面选择联系人并发送。"
                DiagnosticLog.event("LogExport", "share_ui_opened bytes=${file.length()}")
            } catch (e: android.content.ActivityNotFoundException) {
                DiagnosticLog.error("LogExport", e)
                try {
                    startActivity(Intent.createChooser(send, "微信不可用，请选择其他方式分享日志"))
                    state.text = "未找到可接收文件的微信，已打开系统分享。"
                } catch (fallback: Exception) { DiagnosticLog.error("LogExport", fallback); state.text = "未能打开分享，可使用“复制最近日志”。" }
            } catch (e: Exception) { DiagnosticLog.error("LogExport", e); state.text = "分享未完成，可通过其他应用分享、保存 TXT 或复制日志。" }
        }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode!=204)return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null){state.text="已取消保存。";DiagnosticLog.event("LogSave","cancelled");return}
        load({
            require(uri.scheme=="content")
            val text=DiagnosticLog.snapshot()
            val stream=contentResolver.openOutputStream(uri,"wt") ?: throw java.io.IOException("Document unavailable")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }) { DiagnosticLog.event("LogSave","document_saved");state.text="TXT 日志已保存，可在微信中选择该文件转发。" }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

internal object LogSharing {
    fun createFile(context: android.content.Context, text: String): File {
        val dir = File(context.cacheDir, "diagnostic-exports")
        check(dir.isDirectory || dir.mkdirs())
        // Do not delete a file that a receiving app may still be reading.
        dir.listFiles()?.filter { it.name.startsWith("ClassBell-log-") && System.currentTimeMillis() - it.lastModified() > 7 * 86400_000L }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "ClassBell-log-$stamp-${UUID.randomUUID().toString().take(8)}.txt").apply { writeText(text, Charsets.UTF_8) }
    }
    fun intent(context: android.content.Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-files", file)
        return Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Class Bell 故障日志")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("运行日志", uri) }
    }
}
