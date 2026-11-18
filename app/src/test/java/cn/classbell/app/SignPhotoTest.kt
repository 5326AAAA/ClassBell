package cn.classbell.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=BellApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignPhotoTest {
    private val app get()=RuntimeEnvironment.getApplication() as BellApp
    @Test fun bigPhotoIsSampledIntoPrivateJpegWithoutKeepingRawCopy() {
        val bitmap=Bitmap.createBitmap(3200,1800,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val uri=Uri.parse("content://test/synthetic-photo")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(bytes))
        val before=app.cacheDir.listFiles().orEmpty().map{it.name}.toSet()
        val file=SignPhoto.prepare(app,uri)
        try {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            file.inputStream().use { BitmapFactory.decodeStream(it,null,bounds) }
            assertTrue(maxOf(bounds.outWidth,bounds.outHeight)<=1600)
            assertEquals("image/jpeg",bounds.outMimeType)
            assertEquals(app.cacheDir.canonicalPath,file.parentFile!!.canonicalPath)
            assertTrue(file.length() in 1..4L*1024*1024)
            assertFalse(app.cacheDir.listFiles().orEmpty().any{it.name !in before && it.name.startsWith("sign-photo-raw-")})
        } finally {file.delete()}
    }
    @Test fun invalidImageDoesNotLeavePrivateRawFiles() {
        val uri=Uri.parse("content://test/not-an-image")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream("invalid".toByteArray()))
        val before=app.cacheDir.listFiles().orEmpty().map{it.name}.toSet()
        assertThrows(ApiProblem::class.java){SignPhoto.prepare(app,uri)}
        assertTrue(app.cacheDir.listFiles().orEmpty().none{it.name !in before && it.name.startsWith("sign-photo-")})
    }
    @Test fun arbitraryFileAndNetworkUrisAreNotLoaded() {
        for(uri in listOf("file:///private/example.jpg","https://example.com/image.jpg"))
            assertThrows(ApiProblem::class.java){SignPhoto.prepare(app,Uri.parse(uri))}
    }
    @Test fun uploadedCopyDropsExifLocationAndDeviceButPreservesVisibleOrientation() {
        val original=java.io.File.createTempFile("synthetic-exif-",".jpg",app.cacheDir)
        val bitmap=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888)
        original.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
        @Suppress("DEPRECATION")
        val exif=android.media.ExifInterface(original.path)
        exif.setAttribute("Make","Synthetic private device");exif.setAttribute("Model","Private model")
        exif.setAttribute("GPSLatitude","1/1,18/1,0/1");exif.setAttribute("GPSLatitudeRef","N")
        exif.setAttribute("GPSLongitude","103/1,48/1,0/1");exif.setAttribute("GPSLongitudeRef","E")
        exif.setAttribute("Orientation","6");exif.saveAttributes()
        val originalBytes=original.readBytes()
        val uri=Uri.parse("content://test/exif-photo")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(originalBytes))
        var prepared:java.io.File?=null
        try {
            prepared=SignPhoto.prepare(app,uri)
            @Suppress("DEPRECATION") val clean=android.media.ExifInterface(prepared.path)
            for(tag in listOf("Make","Model","GPSLatitude","GPSLongitude"))assertNull(tag,clean.getAttribute(tag))
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(prepared.path,bounds)
            assertEquals(80,bounds.outWidth);assertEquals(40,bounds.outHeight)
            assertArrayEquals(originalBytes,original.readBytes())
        } finally {prepared?.delete();original.delete()}
    }
}
