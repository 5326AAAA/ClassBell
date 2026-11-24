package cn.classbell.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceQrEngineTest {
    @Test fun bundledMlKitDecodesCurrentQrOnDeviceWithinFiveSeconds() {
        val payload = "https://mobilelearn.chaoxing.com/widget/sign/e?id=301&c=123456&enc=ABCDEF0123456789"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) -0x1000000 else -0x1 },
            0, 512, 0, 0, 512, 512)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        try {
            val start = SystemClock.elapsedRealtime()
            val decoded = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue(decoded.any { it.rawValue == payload })
            assertTrue("Device ML Kit decoding took $elapsed ms", elapsed < 5000)
            reportDeviceEvidence("DEVICE_QR bundled_mlkit_decode_ms=$elapsed")
        } finally { scanner.close(); bitmap.recycle() }
    }
}
