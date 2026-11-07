package plus.enhance

/**
 * Guesses how damaged a scan is from its file, to pick the cleaning [Strength] automatically.
 *
 * The main cause of "texture" on bad scans is heavy JPEG compression (8×8 blocks, ringing around
 * lines), and a JPEG file says how hard it was compressed: its quantization table. Measuring noise
 * in the picture itself does not work for manga: screentone looks like noise, and strong JPEG
 * compression wipes the noise out while leaving blocks.
 */
object ScanQuality {

    /** The JPEG quality (1–100, libjpeg scale) the file was saved with, or `null` if it is not a JPEG. */
    fun jpegQuality(bytes: ByteArray): Int? {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(bytes, i) != 0xFF) return null
            val marker = u8(bytes, i + 1)
            if (marker == 0xFF) {
                i++ // fill byte
                continue
            }
            val length = u16(bytes, i + 2)
            if (length < 2) return null
            if (marker == 0xDB) luminanceQuality(bytes, i + 4, minOf(i + 2 + length, bytes.size))?.let { return it }
            if (marker == 0xDA) return null // image data starts, no tables before it
            i += 2 + length
        }
        return null
    }

    /** Which kind of file the page is, as far as cleaning goes. */
    fun strengthFor(bytes: ByteArray): Strength {
        jpegQuality(bytes)?.let { return strengthForJpeg(it) }
        return if (isLossyWebp(bytes)) Strength.Light else Strength.Conservative
    }

    /**
     * Picked on real scans spoiled on purpose and compared with the clean page
     * (plus/tools/enhance-bench, plus/DECISIONS.md): Conservative keeps the most detail, dots and
     * tone gradients down to JPEG 40 %; below 50 % Light also clears the blocks and grain. Medium
     * and Strong were never the closest to the clean page and turn gradients into flat patches,
     * so automatic mode does not use them (they stay for manual choice).
     */
    fun strengthForJpeg(quality: Int): Strength = if (quality < 50) Strength.Light else Strength.Conservative

    /** The strengths automatic mode can pick: only their models need downloading. */
    val autoStrengths: List<Strength> = listOf(Strength.Conservative, Strength.Light)

    private fun isLossyWebp(bytes: ByteArray): Boolean =
        bytes.size >= 16 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" &&
            (String(bytes, 12, 4, Charsets.US_ASCII) == "VP8 " || hasChunk(bytes, "VP8 "))

    private fun hasChunk(bytes: ByteArray, name: String): Boolean {
        var i = 12
        while (i + 8 <= bytes.size) {
            if (String(bytes, i, 4, Charsets.US_ASCII) == name) return true
            val size =
                u8(bytes, i + 4) or (u8(bytes, i + 5) shl 8) or (u8(bytes, i + 6) shl 16) or (u8(bytes, i + 7) shl 24)
            if (size < 0) return false
            i += 8 + size + (size and 1)
        }
        return false
    }

    /** Reads the DQT segment [from, to); returns the quality of table 0 (luminance) if it is there. */
    private fun luminanceQuality(bytes: ByteArray, from: Int, to: Int): Int? {
        var j = from
        while (j < to) {
            val precision = u8(bytes, j) shr 4
            val table = u8(bytes, j) and 0x0F
            j++
            val entrySize = if (precision == 0) 1 else 2
            if (j + 64 * entrySize > to) return null
            if (table == 0) {
                val natural = IntArray(64)
                for (k in 0 until 64) {
                    val value = if (entrySize == 1) u8(bytes, j + k) else u16(bytes, j + 2 * k)
                    natural[ZIGZAG[k]] = value
                }
                return qualityOf(natural)
            }
            j += 64 * entrySize
        }
        return null
    }

    /** Inverse of libjpeg's quality scaling of the standard luminance table (ITU T.81, Annex K). */
    private fun qualityOf(table: IntArray): Int {
        // Entries clamped to 1 or 255 say nothing about the scale
        val scales = table.indices.filter { table[it] in 2..254 }.map { table[it] * 100.0 / STANDARD_LUMINANCE[it] }
        if (scales.isEmpty()) return 100
        val scale = scales.average()
        val quality = if (scale <= 100) (200 - scale) / 2 else 5000 / scale
        return quality.toInt().coerceIn(1, 100)
    }

    private fun u8(bytes: ByteArray, i: Int) = bytes[i].toInt() and 0xFF

    private fun u16(bytes: ByteArray, i: Int) = (u8(bytes, i) shl 8) or u8(bytes, i + 1)

    private val STANDARD_LUMINANCE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** Position in the 8×8 block of the k-th value in a DQT table (tables are stored in zigzag order). */
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )
}
