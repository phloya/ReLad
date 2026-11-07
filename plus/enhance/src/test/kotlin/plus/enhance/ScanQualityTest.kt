package plus.enhance

import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class ScanQualityTest {

    private fun page(): BufferedImage {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val light = (x / 4 + y / 4) % 2 == 0
                image.setRGB(x, y, if (light) 0xFFFFFF else 0x202020)
            }
        }
        return image
    }

    private fun jpeg(quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(page(), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(page(), "png", it) }.toByteArray()

    @ParameterizedTest
    @ValueSource(ints = [6, 12, 20, 30, 50, 60, 75, 90, 95])
    fun `reads the quality a JPEG was saved with`(quality: Int) {
        ScanQuality.jpegQuality(jpeg(quality))!! shouldBeInRange (quality - 1)..(quality + 1)
    }

    @Test
    fun `not a JPEG`() {
        ScanQuality.jpegQuality(png()) shouldBe null
        ScanQuality.jpegQuality(ByteArray(0)) shouldBe null
        ScanQuality.jpegQuality(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)) shouldBe null
    }

    @Test
    fun `stronger cleaning for harder compression`() {
        ScanQuality.strengthFor(jpeg(12)) shouldBe Strength.Strong
        ScanQuality.strengthFor(jpeg(30)) shouldBe Strength.Medium
        ScanQuality.strengthFor(jpeg(60)) shouldBe Strength.Light
        ScanQuality.strengthFor(jpeg(90)) shouldBe Strength.Conservative
        ScanQuality.strengthFor(png()) shouldBe Strength.Conservative
    }

    @Test
    fun `lossy WebP gets light cleaning, lossless none`() {
        val zeros = "\u0000\u0000\u0000\u0000"
        fun webp(chunk: String) = "RIFF${zeros}WEBP$chunk$zeros".toByteArray(Charsets.ISO_8859_1)

        ScanQuality.strengthFor(webp("VP8 ")) shouldBe Strength.Light
        ScanQuality.strengthFor(webp("VP8L")) shouldBe Strength.Conservative
    }
}
