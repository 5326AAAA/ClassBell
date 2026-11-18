package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast

class CourseDialogs(
    private val activity: Activity,
    private val app: BellApp,
    private val beforeSelectionChange: () -> Unit,
    private val onChange: () -> Unit
) {
    private val identity get() = app.prefs.getString("identity", "").orEmpty()
    private val memory get() = app.courseMemory

    private fun label(course: Course): String {
        val duplicate = app.courses().count { it.name == course.name } > 1
        val tags = memory.tags(identity).filter { course.key in it.courseKeys }.map { it.name }
        return course.name + (if (duplicate) "（班级 ${course.classId}）" else "") +
            (if (tags.isEmpty()) "" else "\n标签：${tags.joinToString("、")}")
    }

    private fun saveSelection(keys: Set<String>) {
        beforeSelectionChange()
        app.saveSelectedCourses(keys)
        onChange()
    }

    fun chooseCourses() {
        val courses = app.courses()
        val selected = app.selectedCourses().map { it.key }.toMutableSet()
        ModernUi.dialog(activity).setTitle("选择需要监测的课程")
            .setMultiChoiceItems(courses.map(::label).toTypedArray(), courses.map { it.key in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(courses[index].key) else selected.remove(courses[index].key)
            }.setPositiveButton("保存") { _, _ -> saveSelection(selected.toSet()) }
            .setNeutralButton("保存并打标签") { _, _ -> saveSelection(selected.toSet()); assignTags(selected.toSet()) }
            .setNegativeButton("取消", null).show()
    }

    fun chooseByTags() {
        val tags = memory.tags(identity)
        if (tags.isEmpty()) {
            ModernUi.dialog(activity).setTitle("还没有课程标签")
                .setMessage("先在“选择课程”中勾选一组课程，点“保存并打标签”；也可在“管理课程标签”中新建。")
                .setPositiveButton("去选择课程") { _, _ -> chooseCourses() }.setNegativeButton("取消", null).show()
            return
        }
        ChoiceSheet.show(activity,"按标签选课","可多选，一次加入一组课程。",tags.map{it.name to "${it.courseKeys.count{key->app.courses().any{course->course.key==key}}} 门课程"},
            primary="仅使用这些标签",secondary="加入已选课程") {indices,merge->
            val chosen=indices.map{tags[it].name}.toSet()
            val keys=memory.coursesForTags(identity,chosen,app.courses())
            if(chosen.isEmpty()){toast("请至少选择一个标签");false}
            else if(keys.isEmpty()){toast("这些标签的课程不在当前列表，请先刷新课程");false}
            else {saveSelection(keys + if(merge)app.selectedCourses().map{it.key}.toSet() else emptySet());toast("课程已选择，请重新开始监测");true}
        }
    }

    private fun assignTags(keys: Set<String>) {
        if (keys.isEmpty()) { toast("尚未勾选课程，无法打标签"); return }
        val tags = memory.tags(identity)
        if (tags.isEmpty()) { promptName("新建课程标签") { name -> memory.putTag(identity, name, keys); changed() }; return }
        val chosen = linkedSetOf<String>()
        ModernUi.dialog(activity).setTitle("给所选 ${keys.size} 门课程打标签")
            .setMultiChoiceItems(tags.map { it.name }.toTypedArray(), BooleanArray(tags.size)) { _, index, checked ->
                if (checked) chosen.add(tags[index].name) else chosen.remove(tags[index].name)
            }.setPositiveButton("添加到标签") { _, _ ->
                if (chosen.isEmpty()) toast("尚未选择标签，课程标签未修改")
                else { chosen.forEach { memory.putTag(identity, it, keys, append = true) }; changed() }
            }.setNeutralButton("新建标签") { _, _ ->
                promptName("新建课程标签") { name -> memory.putTag(identity, name, keys, append = true); changed() }
            }.setNegativeButton("取消", null).show()
    }

    fun manageTags() {
        val tags = memory.tags(identity)
        ModernUi.dialog(activity).setTitle("管理课程标签")
            .setItems(if (tags.isEmpty()) arrayOf("暂无标签，点击下方新建") else tags.map { "${it.name}（${it.courseKeys.size} 门）" }.toTypedArray()) { _, index ->
                if (tags.isNotEmpty()) editTag(tags[index])
            }.setPositiveButton("新建标签") { _, _ -> promptName("新建课程标签") { name ->
                require(memory.tags(identity).none { it.name == name }) { "同名标签已存在，请编辑已有标签" }
                require(memory.tags(identity).size < 80) { "最多可创建 80 个标签" }
                editMembers(CourseTag(name, emptySet()))
            } }
            .setNegativeButton("关闭", null).show()
    }

    private fun editTag(tag: CourseTag) {
        ModernUi.dialog(activity,listOf(ModernUi.ChoiceTone.STANDARD,ModernUi.ChoiceTone.STANDARD,ModernUi.ChoiceTone.DESTRUCTIVE)).setTitle(tag.name)
            .setItems(arrayOf("编辑标签内课程", "重命名标签", "删除标签")) { _, index ->
                when (index) {
                    0 -> editMembers(tag)
                    1 -> promptName("重命名标签", tag.name) { name -> memory.renameTag(identity, tag.name, name); changed() }
                    2 -> { memory.deleteTag(identity, tag.name); changed() }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun editMembers(tag: CourseTag) {
        val courses = app.courses()
        val selected = tag.courseKeys.toMutableSet()
        ModernUi.dialog(activity).setTitle("编辑标签课程：${tag.name}")
            .setMultiChoiceItems(courses.map(::label).toTypedArray(), courses.map { it.key in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(courses[index].key) else selected.remove(courses[index].key)
            }.setPositiveButton("保存标签") { _, _ ->
                memory.putTag(identity, tag.name, selected.toSet()); changed()
            }.setNegativeButton("取消", null).show()
    }

    private fun promptName(title: String, initial: String = "", save: (String) -> Unit) {
        val input = EditText(activity).apply {
            hint = "如：周一、上午、专业课"; setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(32)); setText(initial)
            ModernUi.input(this)
        }
        val dialog = ModernUi.dialog(activity).setTitle(title).setView(input)
            .setPositiveButton("确定", null).setNegativeButton("取消", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setLoggedClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { input.error = "请填写标签名"; return@setLoggedClickListener }
            try { save(name); dialog.dismiss() }
            catch (e: IllegalArgumentException) { DiagnosticLog.error("CourseDialogs", e); input.error = e.message }
        }
    }

    private fun changed() { onChange(); toast("课程标签已保存") }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
