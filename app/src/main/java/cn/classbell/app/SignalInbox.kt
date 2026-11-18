package cn.classbell.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A small local handoff when the notification listener survives but the monitor service does not. */
object SignalInbox {
    @Synchronized fun enqueue(context: Context, signal: SignSignal) {
        val app = context.applicationContext as BellApp
        val values = read(context).filterNot { it.key == signal.key }.takeLast(31) + signal
        val data = JSONArray()
        values.forEach { data.put(JSONObject().put("sourceKey", it.sourceKey).put("title", it.title)
            .put("text", it.text).put("eventTime", it.eventTime).put("postedAt", it.postedAt)
            .put("kind", it.kind?.name ?: "CHECK_ONLY")) }
        app.prefs.edit().putString("signalInbox", data.toString()).commit()
    }
    @Synchronized fun read(context: Context): List<SignSignal> {
        val app = context.applicationContext as BellApp
        val data = diagnosticRunCatching { JSONArray(app.prefs.getString("signalInbox", "[]")) }.getOrDefault(JSONArray())
        return (0 until data.length()).mapNotNull { index -> diagnosticRunCatching { data.getJSONObject(index).let {
            SignSignal(it.getString("sourceKey"), it.getString("title"), it.getString("text"), it.getLong("eventTime"), it.getLong("postedAt"),
                kind = if (it.optString("kind") == "CHECK_ONLY") null else ReminderKind.fromName(it.optString("kind")))
        } }.getOrNull() }
    }
    @Synchronized fun take(context: Context): List<SignSignal> = read(context).also {
        (context.applicationContext as BellApp).prefs.edit().remove("signalInbox").commit()
    }
}
