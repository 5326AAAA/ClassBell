package cn.classbell.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

internal interface SpeechClip {
    fun play()
    fun release()
}

private class PackagedSpeechClip(context: Context, resource: Int = R.raw.sign_in) : SpeechClip {
    private val player = MediaPlayer.create(context, resource,
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), 0)
        ?: throw IllegalStateException("Audio resource could not be loaded")
    override fun play() {
        player.seekTo(0)
        player.start()
    }
    override fun release() { player.release() }
}

/** Offline speech, immediately then every five seconds, only while an actual reminder is active. */
internal class ReminderAudio(
    context: Context,
    private val enabled: () -> Boolean,
    private val onFailure: (String) -> Unit,
    private val clipFactory: () -> SpeechClip = { PackagedSpeechClip(context) },
    private val classroomClipFactory: () -> SpeechClip = { PackagedSpeechClip(context, R.raw.classroom_activity) },
    private val kinds: () -> List<ReminderKind> = { listOf(ReminderKind.SIGN_IN) },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:spoken-reminder").apply { setReferenceCounted(false) }
    private val clips = mutableMapOf<ReminderKind, SpeechClip>()
    private var nextKind = 0
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (!enabled()) { stop(); return }
            try {
                // Keep the five-second cadence only during a spoken alert, never during idle monitoring.
                wakeLock.acquire(15_000)
                val available = kinds().distinct().ifEmpty { listOf(ReminderKind.SIGN_IN) }
                val kind = available[nextKind % available.size]
                nextKind = (nextKind + 1) % available.size
                clips.getOrPut(kind) { if (kind == ReminderKind.CLASSROOM) classroomClipFactory() else clipFactory() }.play()
                handler.postDelayed(this, PERIOD_MS)
            } catch (diagnosticError: Exception) { DiagnosticLog.error("ReminderAudio", diagnosticError);
                stop()
                onFailure("提醒语音未能播放，振动继续。请检查闹钟音量，或关闭后重新开启语音提醒。")
            }
        }
    }
    fun start() {
        if (active || !enabled()) return
        try {
            nextKind = 0
            active = true
            tick.run()
        } catch (diagnosticError: Exception) { DiagnosticLog.error("ReminderAudio", diagnosticError);
            stop()
            onFailure("未能加载提醒语音，振动提醒仍然有效。")
        }
    }
    fun stop() {
        active = false
        handler.removeCallbacks(tick)
        clips.values.forEach { diagnosticRunCatching { it.release() } }
        clips.clear()
        if (wakeLock.isHeld) wakeLock.release()
    }
    companion object { const val PERIOD_MS = 5_000L }
}
