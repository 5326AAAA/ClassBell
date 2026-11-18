package cn.classbell.app

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

object SignPhoto {
    const val PRIVACY_NOTICE="无需摄像头或整库相册权限，只读取你选择的图片。上传副本会移除拍摄位置、设备等元数据，原图不变。"

    /** System-owned, single-image selection; no camera, media-library or persistent URI grant. */
    @Suppress("DEPRECATION")
    fun openPicker(activity:Activity,requestCode:Int) {
        if(Build.VERSION.SDK_INT>=33) {
            try {
                activity.startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),requestCode)
                DiagnosticLog.event("SignPhoto","picker_opened kind=system_photo single=true")
                return
            } catch(error:ActivityNotFoundException) {DiagnosticLog.error("PhotoPickerFallback",error)}
        }
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),requestCode)
        DiagnosticLog.event("SignPhoto","picker_opened kind=system_document single=true")
    }

    /** Decode a sampled image, strip metadata and keep temporary files inside private cache only. */
    fun prepare(context: Context, uri: Uri): File {
        DiagnosticLog.event("SignPhoto", "prepare_begin content_uri=${uri.scheme=="content"}")
        if (uri.scheme != "content") throw ApiProblem("请通过系统相册选择图片。")
        val raw = File.createTempFile("sign-photo-raw-", ".tmp", context.cacheDir)
        var output: File? = null
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { stream ->
                val buffer = ByteArray(16*1024)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw ApiProblem("选图已取消。")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 20L*1024*1024) throw ApiProblem("原图超过 20 MB，请选择较小的图片。")
                    stream.write(buffer,0,count)
                }
            } } ?: throw ApiProblem("系统未允许读取这张图片，请重新选择。")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
            raw.inputStream().use { BitmapFactory.decodeStream(it,null,bounds) }
            DiagnosticLog.event("SignPhoto", "source_read bytes=${raw.length()} width=${bounds.outWidth} height=${bounds.outHeight}")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong()*bounds.outHeight > 200_000_000)
                throw ApiProblem("无法读取图片尺寸，请选用普通照片。")
            var sample=1
            while (maxOf(bounds.outWidth,bounds.outHeight)/sample > 1600) sample*=2
            bitmap=raw.inputStream().use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) }
                ?: throw ApiProblem("图片无法解码，请选择 JPG 或 PNG 照片。")
            @Suppress("DEPRECATION")
            val orientation=diagnosticRunCatching { raw.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) } }.getOrDefault(1)
            val matrix=Matrix().apply { when(orientation) {
                2 -> setScale(-1f,1f); 3 -> setRotate(180f); 4 -> setScale(1f,-1f)
                5 -> {setRotate(90f);postScale(-1f,1f)}; 6 -> setRotate(90f)
                7 -> {setRotate(270f);postScale(-1f,1f)}; 8 -> setRotate(270f)
            } }
            if (!matrix.isIdentity) {
                val original=checkNotNull(bitmap)
                bitmap=Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
                if (original !== bitmap) original.recycle()
            }
            output=File.createTempFile("sign-photo-ready-", ".jpg",context.cacheDir)
            val ready=checkNotNull(bitmap)
            output.outputStream().use { if (!ready.compress(Bitmap.CompressFormat.JPEG,88,it)) throw ApiProblem("照片处理失败。") }
            if (output.length() !in 1..4L*1024*1024) throw ApiProblem("处理后的图片仍过大，请另选照片。")
            if (Thread.currentThread().isInterrupted) throw ApiProblem("选图已取消。")
            DiagnosticLog.event("SignPhoto", "prepare_complete bytes=${output.length()} width=${ready.width} height=${ready.height} metadata_removed=true")
            return output
        } catch (e: Exception) { DiagnosticLog.error("SignPhoto", e); output?.delete(); throw e }
        finally { raw.delete(); bitmap?.recycle() }
    }
}
