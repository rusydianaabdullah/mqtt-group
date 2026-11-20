package id.larus.mqttgroup.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders text as a QR code.
 *
 * Always black on white regardless of theme: a dark-mode inversion is legible to a person and
 * unreadable to half the scanners out there, and this code is the only way into a group.
 */
@Composable
fun QrCode(content: String, size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { encode(content, sizePx = 640) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Invite QR code",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .background(androidx.compose.ui.graphics.Color.White),
    )
}

private fun encode(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            // An invite is shown on a screen at close range, so the lowest correction level
            // that scans reliably keeps the modules large — which matters more for a payload
            // this long than redundancy does.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        ),
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
