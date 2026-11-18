package cn.classbell.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream

/** Only decoded material, never a session. Binding to the authenticated course is checked again at commit. */
class SignQr private constructor(val officialUrl:String,val activityId:String,internal val enc:String) {
    override fun toString()="SignQr(redacted)"
    fun requireFor(activity:SignActivity) { if(activityId!=activity.id)throw ApiProblem("二维码不属于当前签到，请重新扫描对应课程的二维码。") }
    companion object {
        fun parse(raw:String):SignQr? {
            if(raw.length !in 1..4096 || raw.any{it.isWhitespace() || it.code<32})return null
            val normalized=if(raw.startsWith("SIGNIN:"))"https://mobilelearn.chaoxing.com/widget/sign/e?"+raw.removePrefix("SIGNIN:").removePrefix("?") else raw
            val url=normalized.toHttpUrlOrNull() ?: return null
            if(url.scheme!="https" || url.host!="mobilelearn.chaoxing.com" || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment!=null || url.encodedPath!="/widget/sign/e")return null
            val allowed=setOf("id","aid","c","enc","DB_STRATEGY","STRATEGY_PARA")
            if(url.queryParameterNames.any{it !in allowed || url.queryParameterValues(it).size!=1})return null
            val id=url.queryParameter("id") ?: url.queryParameter("aid") ?: return null
            if(url.queryParameter("id")!=null && url.queryParameter("aid")!=null)return null
            val enc=url.queryParameter("enc").orEmpty()
            if(!Regex("[0-9]{1,30}").matches(id) || url.queryParameter("c")?.let{!Regex("[0-9]{1,30}").matches(it)}==true || !Regex("[a-fA-F0-9]{8,256}").matches(enc))return null
            if(url.queryParameter("DB_STRATEGY")?.let{it!="PRIMARY_KEY"}==true || url.queryParameter("STRATEGY_PARA")?.let{it!="id"}==true)return null
            return SignQr(url.toString(),id,enc)
        }
    }
}

object QrImage {
    private const val MAX_BYTES=16*1024*1024
    fun read(context:Context,uri:Uri):String {
        if(uri.scheme!="content")throw ApiProblem("请从系统相册选择图片。")
        val bytes=context.contentResolver.openInputStream(uri)?.use{input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException();val size=input.read(buffer);if(size<0)break;if(output.size()+size>MAX_BYTES)throw ApiProblem("图片超过 16 MB，请裁剪二维码后重试。");output.write(buffer,0,size)}
            output.toByteArray()
        } ?: throw ApiProblem("未能读取所选图片，请重新选择。")
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>30_000 || bounds.outHeight>30_000)throw ApiProblem("图片格式或尺寸不受支持。")
        val options=BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1;while(maxOf(bounds.outWidth/inSampleSize,bounds.outHeight/inSampleSize)>2048)inSampleSize*=2}
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: throw ApiProblem("未能读取图片内容。")
        return try{decode(bitmap)}finally{bitmap.recycle()}
    }
    internal fun decode(bitmap:Bitmap):String {
        if(bitmap.width.toLong()*bitmap.height>4_194_304)throw ApiProblem("图片尺寸过大，请裁剪二维码后重试。")
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
        val reader=MultiFormatReader()
        try{return reader.decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text}
        catch (diagnosticError: NotFoundException) { DiagnosticLog.error("SignQr", diagnosticError);throw ApiProblem("没有识别到二维码，请选清晰、完整的图片。")}
        finally{reader.reset()}
    }
}
