package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrImageTest {
    private val payload="https://mobilelearn.chaoxing.com/widget/sign/e?id=301&enc=0123456789abcdef"

    private fun code(inverted:Boolean=false,rotated:Boolean=false,text:String=payload):Bitmap {
        val bits=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,360,360,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return Bitmap.createBitmap(360,360,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until height)for(x in 0 until width) {
                val dark=if(rotated)bits[y,width-1-x] else bits[x,y]
                setPixel(x,y,if(dark!=inverted)Color.BLACK else Color.WHITE)
            }
        }
    }
    private fun blank()=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}

    @Test fun rotatedAndInvertedCodesDecodeWithoutLoadingTheFallback() {
        for(inverted in listOf(false,true))for(rotated in listOf(false,true)) {
            val bitmap=code(inverted,rotated)
            try {
                assertEquals(payload,QrImage.decode(bitmap){fail("ZXing must handle this fixture");null})
            } finally {bitmap.recycle()}
        }
    }

    @Test fun exifRotationAndMirroringNormalizeEveryCornerBeforeCropping() {
        val source=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888)
        val r=Color.RED;val g=Color.GREEN;val b=Color.BLUE;val y=Color.YELLOW
        source.setPixels(intArrayOf(r,r,g,g,b,b,y,y),0,4,0,0,4,2)
        val expected=mapOf(1 to listOf(r,g,b,y),2 to listOf(g,r,y,b),3 to listOf(y,b,g,r),4 to listOf(b,y,r,g),
            5 to listOf(r,b,g,y),6 to listOf(b,r,y,g),7 to listOf(y,g,b,r),8 to listOf(g,y,r,b))
        try {
            for((orientation,corners) in expected) {
                val image=QrImage.orientBitmap(source,orientation)
                try {
                    val swapped=orientation>=5
                    assertEquals(if(swapped)2 else 4,image.width)
                    assertEquals(if(swapped)4 else 2,image.height)
                    assertEquals("EXIF orientation $orientation",corners,listOf(image.getPixel(0,0),image.getPixel(image.width-1,0),
                        image.getPixel(0,image.height-1),image.getPixel(image.width-1,image.height-1)))
                    assertFalse(source.isRecycled)
                }finally{if(image!==source)image.recycle()}
            }
        }finally{source.recycle()}
    }

    @Test fun previewFitCentersPortraitAndLandscapeWithoutDistortion() {
        val frame=RectF(10f,20f,410f,420f)
        assertEquals(RectF(110f,20f,310f,420f),fitQrPreview(600,1200,frame))
        assertEquals(RectF(10f,120f,410f,320f),fitQrPreview(1200,600,frame))
    }

    @Test fun previewSelectionMapsToPixelsIncludingFractionalAndImageEdges() {
        val bounds=RectF(10f,20f,210f,120f)
        assertEquals(Rect(0,0,1000,500),mapQrCrop(RectF(-20f,0f,230f,130f),bounds,1000,500))
        assertEquals(Rect(50,25,151,101),mapQrCrop(RectF(20.1f,25.1f,40.1f,40.1f),bounds,1000,500))
        assertEquals(Rect(900,450,1000,500),mapQrCrop(RectF(190f,110f,210f,120f),bounds,1000,500))
    }

    @Test fun croppedImageDecodesTheChosenCodeAndDoesNotOwnOrMutateTheInput() {
        val first=code()
        val other="签到识别测试：另外一个二维码"
        val second=code(text=other)
        val bitmap=Bitmap.createBitmap(860,530,Bitmap.Config.ARGB_8888).apply{
            eraseColor(Color.WHITE)
            Canvas(this).apply{
                drawBitmap(first,Rect(0,0,360,360),Rect(0,100,360,460),null)
                drawBitmap(second,Rect(0,0,360,360),Rect(500,100,860,460),null)
            }
        }
        val right=Rect(490,90,900,470)
        try {
            assertEquals(payload,QrImage.decodeSelected(bitmap,Rect(-20,90,370,470)))
            assertEquals(other,QrImage.decodeSelected(bitmap,right))
            assertEquals(Rect(490,90,900,470),right)
            assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle();first.recycle();second.recycle()}
    }

    @Test fun originalAndFullImageCropLeaveTheInputAvailable() {
        val bitmap=code()
        try {
            assertEquals(payload,QrImage.decodeSelected(bitmap,null))
            assertFalse(bitmap.isRecycled)
            assertEquals(payload,QrImage.decodeSelected(bitmap,Rect(0,0,bitmap.width,bitmap.height)))
            assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle()}
    }

    @Test fun tinyAndOutsideCropsAreRejectedWithoutRecyclingTheSource() {
        val bitmap=blank()
        try {
            for(crop in listOf(Rect(0,0,12,30),Rect(0,80,64,120))) {
                val error=assertThrows(ApiProblem::class.java){QrImage.decodeSelected(bitmap,crop)}
                assertTrue(error.message!!.contains("裁剪范围太小"))
            }
            assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle()}
    }

    @Test fun dialogOriginalActionDeliversBeforeDismissAndCancelDoesNotDecode() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val bitmap=blank()
        val callbacks=mutableListOf<String>()
        try {
            fun show()=showQrCropDialog(activity,bitmap,{rect->assertNull(rect);callbacks.add("original")},
                {callbacks.add("dismiss")})
            show().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("original","dismiss"),callbacks)
            callbacks.clear()
            show().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("dismiss"),callbacks)
            assertFalse(bitmap.isRecycled)
        } finally {controller.pause().stop().destroy();bitmap.recycle()}
    }

    @Test fun fallbackIsInvokedOnlyAfterAnOrdinaryMissAndDoesNotOwnTheInput() {
        val bitmap=blank();var calls=0
        try {
            val decoded=QrImage.decode(bitmap){image->
                calls++;assertSame(bitmap,image);assertFalse(image.isRecycled);payload
            }
            assertEquals(payload,decoded);assertEquals(1,calls);assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle()}
    }

    @Test fun fallbackCannotMakeAnUntrustedPayloadAValidSignCode() {
        val bitmap=blank()
        try {
            val decoded=QrImage.decode(bitmap){"https://example.org/untrusted"}
            assertNull(SignQr.parse(decoded))
        } finally {bitmap.recycle()}
    }

    @Test fun emptyResultsAndTimeoutHaveActionableErrors() {
        val bitmap=blank()
        try {
            val miss=assertThrows(ApiProblem::class.java){QrImage.decode(bitmap){null}}
            assertTrue(miss.message!!.contains("没有识别到二维码"))
            val timeout=assertThrows(ApiProblem::class.java){QrImage.decode(bitmap){throw TimeoutException("private diagnostic")}}
            assertTrue(timeout.message!!.contains("识别超时"));assertFalse(timeout.message!!.contains("private"))
        } finally {bitmap.recycle()}
    }

    @Test fun nativeCancellationRemainsCancellationAndRestoresInterrupt() {
        val bitmap=blank()
        try {
            assertThrows(CancellationException::class.java){QrImage.decode(bitmap){throw InterruptedException()}}
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted();bitmap.recycle()}
    }

    @Test fun interruptedWorkNeverInvokesAnotherDecoder() {
        val bitmap=blank();var called=false
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java){QrImage.decode(bitmap){called=true;payload}}
            assertFalse(called)
        } finally {Thread.interrupted();bitmap.recycle()}
    }

    @Test fun ambiguousCodesAreNotSilentlyChosenByTheFallback() {
        val bitmap=blank()
        try {
            val error=assertThrows(ApiProblem::class.java){QrImage.decode(bitmap){throw IllegalArgumentException("private")}}
            assertTrue(error.message!!.contains("单个二维码"));assertFalse(error.message!!.contains("private"))
        } finally {bitmap.recycle()}
    }

    @Test fun recycledImagesAreRejectedBeforeInvokingTheFallback() {
        val bitmap=blank();bitmap.recycle();var called=false
        assertThrows(ApiProblem::class.java){QrImage.decode(bitmap){called=true;payload}}
        assertFalse(called)
    }

    @Test fun nativeTaskTimeoutLeavesResourcesOwnedUntilCompletion() {
        val source=TaskCompletionSource<List<Barcode>>();val cleanups=AtomicInteger()
        assertThrows(TimeoutException::class.java){
            MlKitQrDecoder.awaitResult(source.task,{cleanups.incrementAndGet()},0,TimeUnit.MILLISECONDS)
        }
        assertEquals(0,cleanups.get())
        source.setResult(emptyList())
        assertEquals(1,cleanups.get())
    }

    @Test fun interruptedWaitDoesNotRecycleAnInFlightNativeImage() {
        val source=TaskCompletionSource<List<Barcode>>();val cleanups=AtomicInteger()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java){
                MlKitQrDecoder.awaitResult(source.task,{cleanups.incrementAndGet()},1,TimeUnit.SECONDS)
            }
            assertEquals(0,cleanups.get())
        } finally {Thread.interrupted()}
        source.setResult(emptyList())
        assertEquals(1,cleanups.get())
    }

    @Test fun failedNativeTaskReleasesResourcesAndPreservesFailureCategory() {
        val source=TaskCompletionSource<List<Barcode>>();val cleanups=AtomicInteger()
        source.setException(IllegalStateException("synthetic failure"))
        val error=assertThrows(ExecutionException::class.java){
            MlKitQrDecoder.awaitResult(source.task,{cleanups.incrementAndGet()},1,TimeUnit.SECONDS)
        }
        assertTrue(error.cause is IllegalStateException);assertEquals(1,cleanups.get())
    }

    @Test fun completedEmptyNativeTaskReleasesResourcesExactlyOnce() {
        val source=TaskCompletionSource<List<Barcode>>();val cleanups=AtomicInteger()
        source.setResult(emptyList())
        assertNull(MlKitQrDecoder.awaitResult(source.task,{cleanups.incrementAndGet()},1,TimeUnit.SECONDS))
        assertEquals(1,cleanups.get())
    }

    @Test fun mlKitBridgeRefusesToBlockTheAndroidMainThread() {
        val bitmap=blank()
        try {
            assertThrows(IllegalStateException::class.java){MlKitQrDecoder.decode(bitmap)}
            assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle()}
    }
}
