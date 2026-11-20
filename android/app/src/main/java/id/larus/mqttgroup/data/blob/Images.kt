package id.larus.mqttgroup.data.blob

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Shrinks images before they are encrypted, because ciphertext never compresses: a 5 MB photo
 * sealed is 5 MB on the wire and 5 MB on disk. Everything that becomes a blob passes through
 * here first.
 */
object Images {

    /** Avatars are only ever drawn small; 256px square is generous and lands around 20 KB. */
    fun avatar(source: ByteArray): ByteArray? = downscale(source, maxEdge = 256, quality = 85)

    /**
     * Fits [source] into [maxEdge] and re-encodes as JPEG, dropping quality until the result is
     * within [maxBytes] if one is given. Returns null if the bytes are not a decodable image.
     */
    fun downscale(source: ByteArray, maxEdge: Int, quality: Int = 80, maxBytes: Int? = null): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize does the bulk of the work inside the decoder, so a huge photo never
        // needs a full-size bitmap in memory.
        val longest = max(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longest / (it * 2) < maxEdge }
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null

        val scale = maxEdge.toFloat() / max(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        var currentQuality = quality
        var encoded: ByteArray
        while (true) {
            encoded = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, currentQuality, out)
                out.toByteArray()
            }
            if (maxBytes == null || encoded.size <= maxBytes || currentQuality <= 40) break
            currentQuality -= 10
        }
        scaled.recycle()
        return encoded
    }
}
