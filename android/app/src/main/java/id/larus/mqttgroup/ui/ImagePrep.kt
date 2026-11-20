package id.larus.mqttgroup.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Turns whatever a photo picker hands back — any resolution, any format, EXIF orientation and
 * all handled by [BitmapFactory] — into something that fits [BlobStore]'s cap. There is no
 * server to resize on someone else's behalf, so it happens here or a phone on a slow connection
 * pays for a multi-megabyte camera photo it never asked for.
 */
object ImagePrep {
    /** Comfortably under the blob store's documented 1 MB assumption, with room for overhead. */
    const val MAX_BYTES = 900_000
    private const val MAX_DIMENSION = 1600

    /** Returns the recompressed bytes and their mime type, or null if the picker's uri failed. */
    fun prepare(resolver: ContentResolver, uri: Uri): Pair<ByteArray, String>? {
        val bitmap = decodeScaled(resolver, uri) ?: return null
        var quality = 90
        var bytes = compress(bitmap, quality)
        // A few steps down is enough for any normal photo; if it still does not fit, this is not
        // a photo an MQTT broker meant for chat should be carrying anyway.
        while (bytes.size > MAX_BYTES && quality > 20) {
            quality -= 15
            bytes = compress(bitmap, quality)
        }
        return if (bytes.size <= MAX_BYTES) bytes to "image/jpeg" else null
    }

    private fun decodeScaled(resolver: ContentResolver, uri: Uri): Bitmap? {
        // Bounds-only decoding always hands back null — that pass exists to fill `bounds`, not
        // to produce a bitmap — so the stream itself, not this result, is what says whether the
        // uri could be opened at all.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = resolver.openInputStream(uri) ?: return null
        opened.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
