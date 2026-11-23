package cn.classbell.app;

import android.graphics.Bitmap;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** On-device image fallback. Owns a copy until native processing completes, even after cancellation. */
public final class MlKitQrDecoder {
    private MlKitQrDecoder() {}

    public static String decode(Bitmap bitmap)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Image decoding must run off the main thread");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Image decoding cancelled");
        }
        Bitmap owned = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (owned == null) {
            throw new IllegalStateException("Unable to copy image");
        }
        BarcodeScanner scanner;
        Task<List<Barcode>> task;
        try {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            scanner = BarcodeScanning.getClient(options);
        } catch (RuntimeException error) {
            owned.recycle();
            throw error;
        }
        try {
            task = scanner.process(InputImage.fromBitmap(owned, 0));
        } catch (RuntimeException error) {
            try {
                scanner.close();
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            } finally {
                owned.recycle();
            }
            throw error;
        }
        return awaitResult(task, () -> {
            try {
                scanner.close();
            } finally {
                owned.recycle();
            }
        }, 8, TimeUnit.SECONDS);
    }

    // Kept separate so timeout/cancellation ownership is testable without native model execution.
    static String awaitResult(Task<List<Barcode>> task, Runnable cleanup, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        CountDownLatch completed = new CountDownLatch(1);
        task.addOnCompleteListener(Runnable::run, finished -> {
            try {
                cleanup.run();
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(timeout, unit)) {
            // Do not recycle an image still being used by the native engine. The listener owns it.
            throw new TimeoutException("On-device QR decoding timed out");
        }
        if (task.isCanceled()) {
            throw new InterruptedException("Image decoding cancelled");
        }
        if (!task.isSuccessful()) {
            throw new ExecutionException(task.getException());
        }
        Set<String> values = new LinkedHashSet<>();
        for (Barcode barcode : task.getResult()) {
            String raw = barcode.getRawValue();
            if (raw != null && !raw.isEmpty()) {
                values.add(raw);
            }
        }
        if (values.size() > 1) {
            // Never silently choose a different activity when a photo contains multiple codes.
            throw new IllegalArgumentException("Crop the image to a single QR code");
        }
        return values.isEmpty() ? null : values.iterator().next();
    }
}
