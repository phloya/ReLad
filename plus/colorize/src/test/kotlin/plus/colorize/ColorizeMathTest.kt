package plus.colorize

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

class ColorizeMathTest {

    /** A test drawing in page coordinates (0..1): two circles, a diagonal and a frame; 0 = ink, 255 = paper. */
    private fun drawing(u: Float, v: Float): Float {
        val ink = abs(hypot(u - 0.35f, v - 0.3f) - 0.15f) < 0.012f ||
            abs(hypot(u - 0.7f, v - 0.65f) - 0.1f) < 0.01f ||
            (abs((u - 0.1f) - (v - 0.5f) * 0.8f) < 0.01f && v > 0.5f) ||
            u < 0.03f || u > 0.97f || v < 0.03f || v > 0.97f
        return if (ink) 0f else 255f
    }

    private fun otherDrawing(u: Float, v: Float): Float =
        if (abs(hypot(u - 0.6f, v - 0.2f) - 0.12f) < 0.012f || abs(u - 0.5f) < 0.01f) 0f else 255f

    private fun plane(w: Int, h: Int, f: (Int, Int) -> Float) = Plane(w, h, FloatArray(w * h) { f(it % w, it / w) })

    private val original = plane(200, 300) { x, y -> drawing((x + 0.5f) / 200, (y + 0.5f) / 300) }

    /** The page drawn into a 240×360 canvas at (20, 30), shifted by [shiftX], [shiftY], with tones changed like coloring does. */
    private fun colored(
        shiftX: Float,
        shiftY: Float,
        f: (Float, Float) -> Float = ::drawing,
    ) = plane(240, 360) { x, y ->
        val u = (x + 0.5f - 20 - shiftX) / 200
        val v = (y + 0.5f - 30 - shiftY) / 300
        if (u !in 0f..1f || v !in 0f..1f) {
            255f
        } else {
            val base = f(u, v)
            if (base == 0f) {
                10f
            } else if (u < 0.5f) {
                170f
            } else {
                220f // flat colored areas, darker on the left
            }
        }
    }

    private val expected = Placement(200f, 300f, 20f, 30f)

    @Test
    fun `finds a small shift of the colored drawing`() {
        val result = Alignment.find(original, colored(shiftX = 5f, shiftY = -4f), listOf(expected))

        result.score shouldBeGreaterThan Alignment.MIN_SCORE
        abs(result.placement.offsetX - 25f) shouldBeLessThan 1.5f
        abs(result.placement.offsetY - 26f) shouldBeLessThan 1.5f
    }

    @Test
    fun `a redrawn page does not match`() {
        val result = Alignment.find(original, colored(0f, 0f, ::otherDrawing), listOf(expected))

        result.score shouldBeLessThan Alignment.MIN_SCORE
    }

    @Test
    fun `colors land on the original, ink and fine detail stay`() {
        // Original: white paper, a black line, and a fine 1-px pattern of light grey dots (screentone)
        val w = 60
        val h = 40
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x == 30 -> Raster.rgb(0f, 0f, 0f)
                (x + y) % 2 == 0 && y > 20 -> Raster.rgb(200f, 200f, 200f)
                else -> Raster.rgb(255f, 255f, 255f)
            }
        }
        val page = Raster(w, h, pixels)
        // The model's picture: half the size, everything tinted red, the left half darker
        val coloredPixels = IntArray(30 * 20) { i ->
            if (i % 30 <
                15
            ) {
                Raster.rgb(160f, 60f, 60f)
            } else {
                Raster.rgb(250f, 150f, 150f)
            }
        }
        val colored = Raster(30, 20, coloredPixels)

        val small = page.gray()
        val coloredSmall = colored.gray().let {
            it.resized(it.width * 2, it.height * 2)
        }.let { plane(w, h) { x, y -> it.sample(x + 0.5f, y + 0.5f) } }
        val transfer = ColorTransfer.create(
            small,
            coloredSmall,
            Placement(60f, 40f, 0f, 0f),
            colored,
            Placement(30f, 20f, 0f, 0f),
        )
        val out = pixels.copyOf()
        for (y in 0 until h) {
            val row = out.copyOfRange(y * w, (y + 1) * w)
            transfer.applyRow(row, y, w, h)
            row.copyInto(out, y * w)
        }

        fun r(x: Int, y: Int) = out[y * w + x] shr 16 and 0xFF
        fun g(x: Int, y: Int) = out[y * w + x] shr 8 and 0xFF
        // The line stays black
        r(30, 5) shouldBeLessThan 40
        g(30, 5) shouldBeLessThan 40
        // Paper on the right became red-tinted and stays light
        r(50, 5) shouldBeGreaterThan g(50, 5) + 30
        r(50, 5) shouldBeGreaterThanOrEqual 200
        // The left half took the model's darker shade
        Raster.luma(out[5 * w + 8]) shouldBeLessThan Raster.luma(out[5 * w + 50])
        // The screentone pattern survives: neighbouring dots still differ
        abs(Raster.luma(out[30 * w + 50]) - Raster.luma(out[30 * w + 51])) shouldBeGreaterThan 20f
    }

    @Test
    fun `color pages are recognised, yellowed paper is not color`() {
        val yellowed = Raster(
            10,
            10,
            IntArray(100) {
                if (it % 7 ==
                    0
                ) {
                    Raster.rgb(20f, 20f, 20f)
                } else {
                    Raster.rgb(228f, 222f, 205f)
                }
            },
        )
        val colorful = Raster(
            10,
            10,
            IntArray(100) {
                if (it % 3 ==
                    0
                ) {
                    Raster.rgb(220f, 60f, 50f)
                } else {
                    Raster.rgb(240f, 240f, 240f)
                }
            },
        )

        ColorPage.isColor(yellowed).shouldBeFalse()
        ColorPage.isColor(colorful).shouldBeTrue()
    }

    @Test
    fun `pages are padded to the closest supported shape`() {
        val manga = PageShape.layout(1000, 1500)
        manga.ratio shouldBe "2:3"
        manga.width shouldBe 1000
        manga.height shouldBe 1500

        val slightlyTall = PageShape.layout(1000, 1450)
        slightlyTall.ratio shouldBe "2:3"
        (slightlyTall.height - slightlyTall.pageHeight) shouldBe 50
        slightlyTall.offsetY shouldBe 25

        PageShape.layout(1600, 1000).ratio shouldBe "3:2"
        PageShape.canColor(800, 3000).shouldBeFalse() // webtoon strip
        PageShape.canColor(1000, 1500).shouldBeTrue()
    }

    @Test
    fun `the prompt mentions the title and the reference only when there are some`() {
        ColorizePrompt.user("Yotsuba&!", withReference = true).let {
            it shouldContain "Yotsuba&!"
            it shouldContain "second image"
        }
        ColorizePrompt.user(null, withReference = false).let {
            it shouldNotContain "The manga is"
            it shouldNotContain "second image"
        }
    }

    @Test
    fun `the reference is the closest colored page before, else after`() {
        ChapterColors.remember(77, 2, byteArrayOf(2))
        ChapterColors.remember(77, 5, byteArrayOf(5))

        ChapterColors.reference(77, 4)!!.single() shouldBe 2
        ChapterColors.reference(77, 1)!!.single() shouldBe 2
        ChapterColors.reference(77, 9)!!.single() shouldBe 5
        ChapterColors.reference(78, 1) shouldBe null
    }
}
