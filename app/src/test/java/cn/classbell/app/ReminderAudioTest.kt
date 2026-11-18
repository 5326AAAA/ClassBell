package cn.classbell.app

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReminderAudioTest {
    @Test fun classroomVoiceRepeatsAtFiveSecondsAndDoesNotLoadSignVoice() {
        val classroom = FakeClip()
        val reminder = ReminderAudio(RuntimeEnvironment.getApplication(), { true }, { fail(it) },
            { throw AssertionError("Sign voice should not load") }, { classroom }, { listOf(ReminderKind.CLASSROOM) })
        reminder.start()
        assertEquals(1, classroom.plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, classroom.plays)
        reminder.stop()
        assertEquals(1, classroom.releases)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(2, classroom.plays)
    }
    @Test fun simultaneousKindsAlternateAndStoppingReleasesBothClips() {
        val sign = FakeClip(); val classroom = FakeClip()
        var kinds = listOf(ReminderKind.SIGN_IN, ReminderKind.CLASSROOM)
        val reminder = ReminderAudio(RuntimeEnvironment.getApplication(), { true }, { fail(it) }, { sign }, { classroom }, { kinds })
        reminder.start()
        assertEquals(1, sign.plays); assertEquals(0, classroom.plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, classroom.plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, sign.plays)
        kinds = listOf(ReminderKind.CLASSROOM)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, classroom.plays)
        reminder.stop()
        assertEquals(1, sign.releases); assertEquals(1, classroom.releases)
    }
    @Test fun packagedClassroomVoiceIsDifferentFromSignVoice() {
        val resources = RuntimeEnvironment.getApplication().resources
        val classroom = resources.openRawResource(R.raw.classroom_activity).use { it.readBytes() }
        val sign = resources.openRawResource(R.raw.sign_in).use { it.readBytes() }
        assertEquals("WAVE", String(classroom.copyOfRange(8, 12), Charsets.US_ASCII))
        assertFalse(classroom.contentEquals(sign))
    }
    private class FakeClip : SpeechClip {
        var plays = 0
        var releases = 0
        override fun play() { plays++ }
        override fun release() { releases++ }
    }
    @Test fun playsImmediatelyThenEveryFiveSecondsAndStopCancelsAllFuturePlayback() {
        val clip = FakeClip()
        val reminder = ReminderAudio(RuntimeEnvironment.getApplication(), { true }, { fail(it) }, { clip })
        reminder.start()
        assertEquals(1, clip.plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999))
        assertEquals(1, clip.plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2, clip.plays)
        reminder.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(2, clip.plays)
        assertEquals(1, clip.releases)
    }
    @Test fun voiceDisabledDoesNotLoadOrPlayAudio() {
        var loads = 0
        val reminder = ReminderAudio(RuntimeEnvironment.getApplication(), { false }, { fail(it) }, { loads++; FakeClip() })
        reminder.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(0, loads)
        reminder.stop()
    }
    @Test fun loadFailureHasFeedbackAndLeavesNoRepeatingTask() {
        var errors = 0
        var loads = 0
        val reminder = ReminderAudio(RuntimeEnvironment.getApplication(), { true }, { errors++ }, { loads++; throw IllegalStateException("synthetic error") })
        reminder.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, loads)
        assertEquals(1, errors)
        reminder.stop()
    }
    @Test fun packagedOfflineClipContainsWaveAudio() {
        val bytes = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.sign_in).use { it.readBytes() }
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
        assertTrue(bytes.size > 1000)
    }
}
