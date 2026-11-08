package plus.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Turns an encoded page into the small grayscale copy the detector works on. */
object PanelImages {

    /** Longest side of an ordinary page for detection: gutters stay several pixels wide. */
    private const val MAX_SIDE = 1100

    /** Tall strips keep at least this width, and no more than [MAX_PIXELS] pixels in all. */
    private const val MIN_STRIP_WIDTH = 240
    private const val MAX_PIXELS = 2_400_000

    fun decode(bytes: ByteArray): GrayImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val (targetW, targetH) = targetSize(w, h)

        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Half the memory of ARGB; plenty for telling gutters from ink
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val bitmap = if (decoded.width != targetW || decoded.height != targetH) {
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        try {
            val pixels = ByteArray(targetW * targetH)
            val row = IntArray(targetW)
            for (y in 0 until targetH) {
                bitmap.getPixels(row, 0, targetW, 0, y, targetW, 1)
                for (x in 0 until targetW) {
                    val c = row[x]
                    val luminance = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                    pixels[y * targetW + x] = luminance.toByte()
                }
            }
            return GrayImage(targetW, targetH, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun targetSize(w: Int, h: Int): Pair<Int, Int> {
        var scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
        if (h > w * 2.5f) {
            // A strip: keep it wide enough for its gutters, but bounded in memory
            scale = max(scale, min(1f, MIN_STRIP_WIDTH.toFloat() / w))
            scale = min(scale, sqrt(MAX_PIXELS.toFloat() / (w.toFloat() * h)))
        }
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }
}
