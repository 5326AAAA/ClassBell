package cn.classbell.app

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/** Persist measurements in am instrument output; device logcat buffers may rotate. */
internal fun reportDeviceEvidence(message: String) {
    Log.i("DeviceQrProof", message)
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("stream", "\n$message\n")
    })
}
