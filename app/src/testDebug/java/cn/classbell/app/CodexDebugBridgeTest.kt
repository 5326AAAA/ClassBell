package cn.classbell.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=BellApp::class)
class CodexDebugBridgeTest {
    private val app get() = RuntimeEnvironment.getApplication() as BellApp
    private val target get() = File(app.filesDir, "codex-debug/status.json")
    private val requestId = "abcdef0123456789abcdef0123456789"

    @Before fun reset() { target.delete(); app.prefs.edit().clear().commit() }

    private fun command(operation: String = "status", id: String = requestId) =
        Intent(CodexDebugReceiver.ACTION).putExtra("operation", operation).putExtra("requestId", id)

    @Test fun exportedDebugReceiverRequiresPlatformDumpPermission() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getReceiverInfo(ComponentName(app, CodexDebugReceiver::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.DUMP, info.permission)
    }

    @Test fun snapshotWritesFreshVersionedJsonAndLeavesSettingsUnchanged() {
        app.prefs.edit().putBoolean("monitorEnabled", true).putStringSet("selected", setOf("one", "two")).commit()
        val before = app.prefs.all.toMap()
        CodexDebugReceiver().onReceive(app, command())
        assertTrue(target.isFile)
        val result = JSONObject(target.readText())
        assertEquals(1, result.getInt("schemaVersion"))
        assertEquals(requestId, result.getString("requestId"))
        assertEquals(BuildConfig.VERSION_NAME, result.getJSONObject("app").getString("version"))
        assertEquals(2, result.getJSONObject("settings").getInt("selectedCourseCount"))
        assertTrue(result.getJSONObject("settings").getBoolean("monitorEnabled"))
        assertFalse(result.getJSONObject("bridge").getBoolean("rootRequired"))
        assertEquals(before, app.prefs.all)
    }

    @Test fun accountCredentialsAndArbitraryPreferencesNeverEnterSnapshot() {
        app.prefs.edit().putString("identity", "private-account").putString("password", "secret-password")
            .putString("cookies", "secret-cookie").putString("status", "private status")
            .putString("ui.route", "https://example.invalid/?token=secret-token").commit()
        val text = CodexDebugSnapshot.collect(app).toString()
        listOf("private-account", "secret-password", "secret-cookie", "private status", "secret-token").forEach {
            assertFalse(text.contains(it))
        }
    }

    @Test fun unsupportedCommandsAndPathLikeRequestIdsCannotWriteFiles() {
        listOf(command("submit"), command("shell"), command(id="../../outside"),
            command(id=""), command().setAction("untrusted.action")).forEach {
            CodexDebugReceiver().onReceive(app, it)
            assertFalse(target.exists())
        }
    }

    @Test fun nonDebuggableApplicationCannotUseBridgeEvenInDebugCode() {
        val flags = app.applicationInfo.flags
        try {
            app.applicationInfo.flags = flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
            CodexDebugReceiver().onReceive(app, command())
            assertFalse(target.exists())
        } finally { app.applicationInfo.flags = flags }
    }
}
