package cn.classbell.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class MissingSystemPhotoPickerActivity:Activity() {
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent:Intent,requestCode:Int) {
        if(intent.action==MediaStore.ACTION_PICK_IMAGES)throw ActivityNotFoundException("Synthetic unsupported picker")
        super.startActivityForResult(intent,requestCode)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
class PhotoPickerPrivacyTest {
    @Test fun missingModernPickerFallsBackToOneUserSelectedDocumentWithoutPersistentOrWriteAccess() {
        val controller=Robolectric.buildActivity(MissingSystemPhotoPickerActivity::class.java).setup()
        try {
            val activity=controller.get();SignPhoto.openPicker(activity,123)
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(123,request.requestCode);assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
            assertEquals("image/*",request.intent.type);assertFalse(request.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,true))
            assertEquals(0,request.intent.flags and (Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        } finally {controller.pause().stop().destroy()}
    }
}
