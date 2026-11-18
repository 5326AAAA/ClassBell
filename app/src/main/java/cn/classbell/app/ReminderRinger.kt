package cn.classbell.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager

/** A looping alarm stream owned by the foreground service, stopped with the same ACK as vibration. */
internal class ReminderRinger(private val context: Context, private val onStarted: () -> Unit,
                              private val onFailure: (String) -> Unit) {
    private var player: MediaPlayer? = null
    fun start(speechEnabled: Boolean) {
        val volume = if (speechEnabled) 0.22f else 1f
        try {
            player?.let { it.setVolume(volume, volume); return }
            val created = MediaPlayer()
            player = created
            created.setVolume(volume, volume)
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            created.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            created.isLooping = true
            created.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            created.setOnPreparedListener {
                if (player === it) {
                    try { it.start(); onStarted() }
                    catch (diagnosticError: IllegalStateException) { DiagnosticLog.error("ReminderRinger", diagnosticError); playbackFailed() }
                }
            }
            created.setOnErrorListener { failed, _, _ -> if (player === failed) playbackFailed(); true }
            created.prepareAsync()
        } catch (diagnosticError: Exception) { DiagnosticLog.error("ReminderRinger", diagnosticError); stop(); onFailure("未能启动循环铃声，振动和语音仍会尝试启动") }
    }
    private fun playbackFailed() {
        stop()
        onFailure("铃声播放失败，请检查闹钟音量和系统铃声设置")
    }
    fun stop() { diagnosticRunCatching { player?.release() }; player = null }
}
