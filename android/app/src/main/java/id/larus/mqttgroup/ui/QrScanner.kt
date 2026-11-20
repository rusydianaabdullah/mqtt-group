package id.larus.mqttgroup.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A camera preview that reports the first QR code it reads.
 *
 * Decoding happens on the phone from the analyser's luminance plane — no bitmap conversion, no
 * network, no Play services. The frames are 640×480 because a QR code at arm's length needs
 * nothing more, and analysing full-resolution frames would heat the phone for no gain.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    // One shot: a QR code stays in frame for many frames, and a join must not run twice.
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val reader = QRCodeReader()
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(executor) { image ->
                        val text = image.decodeQr(reader)
                        image.close()
                        if (text != null && delivered.compareAndSet(false, true)) {
                            currentOnScanned(text)
                        }
                    }
                }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Reads the Y plane straight out of the frame. YUV's luminance channel is exactly the greyscale
 * image a QR decoder wants, so there is nothing to convert.
 */
private fun ImageProxy.decodeQr(reader: QRCodeReader): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

    // Row stride can exceed the width on some devices; the source has to be told, or every
    // row lands a few pixels to the right of the last. The last row is sometimes short of a
    // full stride, so fall back to a tight buffer rather than letting the source reject it.
    val stride = plane.rowStride
    val source = if (bytes.size >= stride * height) {
        PlanarYUVLuminanceSource(bytes, stride, height, 0, 0, width, height, false)
    } else {
        if (bytes.size < width * height) return null
        PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    }
    return try {
        reader.decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    } catch (_: Exception) {
        // Not found in this frame, which is the usual case while the user is still aiming.
        null
    } finally {
        reader.reset()
    }
}
