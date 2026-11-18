package cn.classbell.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class CourseTag(val name: String, val courseKeys: Set<String>)

/** Course preferences survive session removal and are scoped to the hashed account identity. */
class CourseMemory(private val storage: SharedPreferences) {
    fun selection(identity: String): Set<String>? = if (identity.isBlank() || !storage.contains("selected.$identity")) null
        else storage.getStringSet("selected.$identity", emptySet())?.toSet() ?: emptySet()

    fun rememberCurrent(session: SharedPreferences) {
        val identity = session.getString("identity", "").orEmpty()
        if (identity.isNotBlank() && session.contains("selected"))
            storage.edit().putStringSet("selected.$identity", session.getStringSet("selected", emptySet())?.toSet()).apply()
    }

    fun activate(session: SharedPreferences, identity: String, courses: List<Course>) {
        require(identity.isNotBlank())
        rememberCurrent(session)
        val remembered = selection(identity)
        val selected = remembered ?: courses.map { it.key }.toSet()
        DiagnosticLog.event("CourseMemory", "activate account=${DiagnosticLog.reference(identity)} available=${courses.size} selection_restored=${remembered!=null} selected=${selected.size}")
        val editor = session.edit().putString("identity", identity).putBoolean("loggedIn", true)
            .putString("courses", JSONArray().also { array -> courses.forEach { array.put(it.json()) } }.toString())
        // A temporary empty first response must not establish an intentional empty preference.
        if (remembered != null || courses.isNotEmpty()) {
            editor.putStringSet("selected", selected)
            storage.edit().putStringSet("selected.$identity", selected).apply()
        } else editor.remove("selected")
        editor.apply()
    }

    fun saveSelection(session: SharedPreferences, selected: Set<String>) {
        val identity = session.getString("identity", "").orEmpty()
        require(identity.isNotBlank()) { "请先登录并读取课程" }
        storage.edit().putStringSet("selected.$identity", selected.toSet()).apply()
        session.edit().putStringSet("selected", selected.toSet()).apply()
    }

    fun detach(session: SharedPreferences) {
        rememberCurrent(session)
        session.edit().remove("identity").remove("courses").remove("selected").remove("lastAuthMessage")
            .remove("alert").remove("pendingAlerts").remove("realSign.receivedAt").remove("realSign.title")
            .remove("realClassTask.receivedAt").remove("realClassTask.title")
            .putBoolean("loggedIn", false).putBoolean("monitorEnabled", false).apply()
    }

    fun tags(identity: String): List<CourseTag> {
        if (identity.isBlank()) return emptyList()
        val array = diagnosticRunCatching { JSONArray(storage.getString("tags.$identity", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val keys = item.optJSONArray("courses") ?: JSONArray()
            if (name.isBlank()) null else CourseTag(name, (0 until keys.length()).map { keys.optString(it) }.filter { it.isNotBlank() }.toSet())
        }.distinctBy { it.name }.sortedBy { it.name }
    }

    fun putTag(identity: String, name: String, keys: Set<String>, append: Boolean = false) {
        DiagnosticLog.event("CourseTags", "edit account=${DiagnosticLog.reference(identity)} tag_ref=${DiagnosticLog.reference(name)} members=${keys.size} append=$append")
        require(identity.isNotBlank()) { "请先登录并读取课程" }
        val normalized = name.trim()
        require(normalized.isNotBlank() && normalized.length <= 32 && !normalized.contains('\n')) { "标签名请填写 1～32 个字" }
        val existing = tags(identity)
        require(existing.size < 80 || existing.any { it.name == normalized }) { "最多可创建 80 个标签" }
        val members = if (append) keys + existing.firstOrNull { it.name == normalized }?.courseKeys.orEmpty() else keys
        writeTags(identity, existing.filterNot { it.name == normalized } + CourseTag(normalized, members.toSet()))
    }

    fun renameTag(identity: String, oldName: String, newName: String) {
        DiagnosticLog.event("CourseTags", "rename account=${DiagnosticLog.reference(identity)} old=${DiagnosticLog.reference(oldName)} new=${DiagnosticLog.reference(newName)}")
        val normalized = newName.trim()
        require(normalized.isNotBlank() && normalized.length <= 32) { "标签名请填写 1～32 个字" }
        val existing = tags(identity)
        require(existing.none { it.name == normalized && it.name != oldName }) { "同名标签已存在" }
        writeTags(identity, existing.map { if (it.name == oldName) it.copy(name = normalized) else it })
    }

    fun deleteTag(identity: String, name: String) = writeTags(identity, tags(identity).filterNot { it.name == name })

    fun coursesForTags(identity: String, names: Set<String>, available: List<Course>): Set<String> {
        val members = tags(identity).filter { it.name in names }.flatMap { it.courseKeys }.toSet()
        return available.map { it.key }.filter { it in members }.toSet()
    }

    private fun writeTags(identity: String, tags: List<CourseTag>) {
        DiagnosticLog.event("CourseTags", "saved account=${DiagnosticLog.reference(identity)} count=${tags.size}")
        require(identity.isNotBlank())
        val array = JSONArray().also { array -> tags.forEach { tag ->
            array.put(JSONObject().put("name", tag.name).put("courses", JSONArray(tag.courseKeys.sorted())))
        } }
        storage.edit().putString("tags.$identity", array.toString()).apply()
    }
}
