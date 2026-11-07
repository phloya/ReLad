package plus.colorize

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plus.core.ai.AiClient
import plus.core.ai.AiException
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiRequest
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The colorization step of the page pipeline ("image to image"): an image model colors the page,
 * then [Alignment] checks the drawing stayed in place and [ColorTransfer] puts the colors onto the
 * original, keeping its lines and text.
 *
 * Runs after enhancement (the model gets a clean page, the colors land on the clean lines) and
 * before translation (the translated text is not recolored).
 */
class PageColorizer(
    private val settings: () -> Settings,
    private val clientFor: (AiProviderConfig) -> AiClient,
) : PageProcessor {

    class Settings(
        /** `null` when no provider is chosen yet: pages then fail with a message saying so. */
        val provider: AiProviderConfig?,
        /** Give the model an already colored page of the chapter, so characters keep their colors. */
        val useReference: Boolean,
    )

    class NotConfiguredException : Exception("Choose an AI provider in Re:Lad → Colorization")

    override val id = "colorize"
    override val order = 250

    /** Last colored page, for the settings screen. */
    class Run(val millis: Long, val score: Float, val skipped: String?)

    @Volatile
    var lastRun: Run? = null
        private set

    override fun signature(context: PageContext): String {
        val s = settings()
        return listOf(
            "v${ColorizePrompt.VERSION}",
            s.provider?.id ?: "none",
            s.provider?.model ?: "",
            "ref=${s.useReference}",
        ).joinToString("|")
    }

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val s = settings()
        val provider = s.provider ?: throw NotConfiguredException()
        val size = PageBitmaps.size(input) ?: error("Not an image")
        if (!PageShape.canColor(size.width, size.height)) return skip(input, "shape")
        val started = System.currentTimeMillis()

        // The page as the model sees it, padded to a shape the model can return
        val small = withContext(Dispatchers.Default) { smallCopy(input) }
        val smallRaster = small.toRaster()
        if (ColorPage.isColor(smallRaster)) {
            small.recycle()
            return skip(input, "color")
        }
        val layout = PageShape.layout(small.width, small.height)
        val request = withContext(Dispatchers.Default) { padded(small, layout) }
        small.recycle()

        val reference = if (s.useReference) ChapterColors.reference(context.chapterId, context.pageIndex) else null
        val response = clientFor(provider).send(
            AiRequest(
                system = ColorizePrompt.SYSTEM,
                prompt = ColorizePrompt.user(context.mangaTitle, withReference = reference != null),
                images = listOfNotNull(AiImage(request, "image/jpeg"), reference?.let { AiImage(it, "image/jpeg") }),
                imageOutput = true,
                imageAspectRatio = layout.ratio,
            ),
        )
        val image = response.images.firstOrNull()
            ?: throw AiException(
                "${provider.name}: the model answered without a picture. Choose a model that can draw images",
                AiException.Kind.Unsupported,
            )

        return withContext(Dispatchers.Default) {
            val colored = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
                ?: throw AiException("${provider.name}: the picture could not be read", AiException.Kind.BadResponse)
            val coloredRaster = colored.toRaster()
            colored.recycle()

            val transfer = transferFor(smallRaster, coloredRaster, layout)
            val page = PageBitmaps.decode(input)
            val row = IntArray(page.width)
            for (y in 0 until page.height) {
                page.getPixels(row, 0, page.width, 0, y, page.width, 1)
                transfer.first.applyRow(row, y, page.width, page.height)
                page.setPixels(row, 0, page.width, 0, y, page.width, 1)
            }
            rememberForReference(context, page)
            val bytes = PageBitmaps.encode(page, Bitmap.CompressFormat.JPEG, quality = 95)
            page.recycle()
            lastRun = Run(System.currentTimeMillis() - started, transfer.second, null)
            ProcessedPage(bytes, metadata = """{"match":${(transfer.second * 100).roundToInt() / 100f}}""")
        }
    }

    private fun skip(input: ByteArray, why: String): ProcessedPage {
        lastRun = Run(0, 0f, why)
        return ProcessedPage(input)
    }

    /** Finds where the colored image lies over the page and prepares the color transfer. */
    private fun transferFor(page: Raster, colored: Raster, layout: PageShape.Layout): Pair<ColorTransfer, Float> {
        // Compare at a small scale: lines are enough, and a few hundred pixels are fast
        val originalSmall = page.gray().let { gray ->
            val factor = ANALYSIS_SIDE.toFloat() / maxOf(gray.width, gray.height)
            gray.resized(
                (gray.width * factor).roundToInt().coerceAtLeast(8),
                (gray.height * factor).roundToInt().coerceAtLeast(8),
            )
        }
        // Expected place: the page sat at the layout's offset; the model's picture covers the whole canvas
        val fx = colored.width.toFloat() / layout.width
        val fy = colored.height.toFloat() / layout.height
        val stretch = Placement(layout.pageWidth * fx, layout.pageHeight * fy, layout.offsetX * fx, layout.offsetY * fy)
        val candidates = mutableListOf(stretch)
        if (abs(fx - fy) / maxOf(fx, fy) > 0.01f) {
            // The model may have kept the proportions and centred the canvas in a differently shaped picture
            val c = minOf(fx, fy)
            candidates += Placement(
                layout.pageWidth * c,
                layout.pageHeight * c,
                (colored.width - layout.width * c) / 2 + layout.offsetX * c,
                (colored.height - layout.height * c) / 2 + layout.offsetY * c,
            )
        }
        // Shrink the colored picture so the page has about the same size in it as in originalSmall
        val k = originalSmall.width / stretch.scaleX
        val coloredSmall = colored.gray().resized(
            (colored.width * k).roundToInt().coerceAtLeast(8),
            (colored.height * k).roundToInt().coerceAtLeast(8),
        )
        val found = Alignment.find(originalSmall, coloredSmall, candidates.map { it.scaled(k) })
        if (found.score < Alignment.MIN_SCORE) {
            throw AiException(
                "The colored page does not line up with the original (match ${(found.score * 100).roundToInt()}%), " +
                    "so it was not used. Another model may keep the drawing better",
                AiException.Kind.BadResponse,
            )
        }
        val transfer = ColorTransfer.create(
            originalSmall,
            coloredSmall,
            found.placement,
            colored,
            found.placement.scaled(1 / k),
        )
        return transfer to found.score
    }

    private fun smallCopy(input: ByteArray): Bitmap {
        val decoded = PageBitmaps.decode(input, maxSide = MODEL_SIDE * 2)
        val factor = MODEL_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        if (factor >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * factor).roundToInt(),
            (decoded.height * factor).roundToInt(),
            true,
        )
        decoded.recycle()
        return scaled
    }

    private fun padded(page: Bitmap, layout: PageShape.Layout): ByteArray {
        val canvas = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            drawColor(Color.WHITE)
            drawBitmap(page, layout.offsetX.toFloat(), layout.offsetY.toFloat(), null)
        }
        val bytes = PageBitmaps.encode(canvas, Bitmap.CompressFormat.JPEG, quality = 92)
        canvas.recycle()
        return bytes
    }

    private fun rememberForReference(context: PageContext, page: Bitmap) {
        val factor = REFERENCE_SIDE.toFloat() / maxOf(page.width, page.height)
        val thumb = Bitmap.createScaledBitmap(
            page,
            (page.width * factor).roundToInt(),
            (page.height * factor).roundToInt(),
            true,
        )
        ChapterColors.remember(
            context.chapterId,
            context.pageIndex,
            PageBitmaps.encode(thumb, Bitmap.CompressFormat.JPEG, 85),
        )
        thumb.recycle()
    }

    private fun Bitmap.toRaster(): Raster {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

    private companion object {
        /** Longest side of the page sent to the model: enough for colors, small to upload. */
        const val MODEL_SIDE = 1536

        /** Longest side of the planes compared by [Alignment]. */
        const val ANALYSIS_SIDE = 256

        const val REFERENCE_SIDE = 512
    }
}
