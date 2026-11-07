package plus.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Decoding and encoding page images for processors. */
object PageBitmaps {

    /** Pages larger than this on their longest side are decoded at a lower resolution (memory). */
    const val MAX_SIDE = 4096

    class Size(val width: Int, val height: Int)

    fun size(bytes: ByteArray): Size? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Size(options.outWidth, options.outHeight)
    }

    /** Decodes [bytes] into a mutable ARGB bitmap no larger than [maxSide]. */
    fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE): Bitmap {
        val size = size(bytes) ?: error("Not an image")
        var sample = 1
        while (maxOf(size.width, size.height) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Could not decode image")
    }

    fun encode(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 95,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, out)) { "Could not encode image" }
        return out.toByteArray()
    }

    /** MIME type of an encoded image, guessed from its first bytes. */
    fun mimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        else -> "image/jpeg"
    }
}
