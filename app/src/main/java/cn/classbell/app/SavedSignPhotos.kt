package cn.classbell.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

/** Private, bounded JPEGs. A lease keeps an in-flight upload independent of replacement/clear. */
class SavedSignPhotos(private val context:Context,private val prefs:SharedPreferences) {
    private val root get()=File(context.filesDir,"sign-photo-presets").apply{mkdirs()}
    fun version(owner:String)=prefs.getString("sign.photo.$owner","").orEmpty()
    @Synchronized fun file(owner:String):File? {
        val name=version(owner)
        if(!Regex("[a-f0-9-]{36}\\.jpg").matches(name))return null
        return File(root,name).takeIf{it.isFile && it.length() in 1..4L*1024*1024}
    }
    @Synchronized fun replace(owner:String,prepared:File) {
        DiagnosticLog.event("Photo", "preset_replace account=${DiagnosticLog.reference(owner)}")
        require(owner.isNotBlank() && prepared.isFile && prepared.length() in 1..4L*1024*1024)
        val previous=file(owner)
        val target=File(root,"${UUID.randomUUID()}.jpg")
        try {
            prepared.copyTo(target)
            check(prefs.edit().putString("sign.photo.$owner",target.name).commit())
            previous?.delete()
        } catch (e: Exception) { DiagnosticLog.error("SavedSignPhotos", e);target.delete();throw e}
    }
    @Synchronized fun clear(owner:String) {
        DiagnosticLog.event("Photo", "preset_clear account=${DiagnosticLog.reference(owner)}")
        val previous=file(owner)
        check(prefs.edit().remove("sign.photo.$owner").putBoolean("autoPhoto.$owner",false).commit())
        previous?.delete()
    }
    @Synchronized fun lease(owner:String):File? {
        val source=file(owner) ?: return null
        val target=File.createTempFile("sign-photo-lease-",".jpg",context.cacheDir)
        try {source.copyTo(target,true);return target} catch (e: Exception) { DiagnosticLog.error("SavedSignPhotos", e);target.delete();throw e}
    }
}
