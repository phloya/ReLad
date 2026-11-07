package plus.translate

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import plus.core.ai.AiClient
import plus.core.ai.AiException
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiProviderType
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage
import plus.translate.bible.BibleStore
import java.io.IOException

/**
 * The translation step of the page pipeline: one request to a multimodal model finds and
 * translates all text on the page, then [PageTypesetter] draws the result onto the page.
 */
class PageTranslator(
    private val settings: () -> Settings,
    private val clientFor: (AiProviderConfig) -> AiClient,
    /** Each title's notes: characters, glossary, story so far. */
    bibles: BibleStore,
    /** The lettering font, loaded on first use. */
    private val fonts: () -> LetteringFonts = { LetteringFonts.SYSTEM },
    /** Translations kept for good: a page translated once is only drawn again. `null`: none kept. */
    private val translations: TranslationStore? = null,
) : PageProcessor {

    private val text = PageTextTranslator(bibles)

    class Settings(
        /** `null` when no provider is chosen yet: pages then fail with a message saying so. */
        val provider: AiProviderConfig?,
        val language: TargetLanguage,
        val translateSoundEffects: Boolean,
    )

    class NotConfiguredException :
        AiException("Choose an AI provider in Re:Lad → Page translation", AiException.Kind.NoProvider)

    override val id = "translate"
    override val order = 300

    /**
     * Mostly waiting for the model's server: several pages at once, so a chapter is ready sooner.
     * Drawing is limited apart ([RENDER_AT_ONCE]), so waiting pages hold little memory.
     */
    override val concurrency = 10

    private val json = Json
    private val lettering by lazy { fonts() }

    override fun signature(context: PageContext): String {
        val s = settings()
        return listOf(
            "v${TranslationPrompt.VERSION}",
            "r$RENDER_VERSION",
            s.provider?.id ?: "none",
            s.provider?.model ?: "",
            s.language.code,
            "sfx=${s.translateSoundEffects}",
        ).joinToString("|")
    }

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val s = settings()
        // Already in the reader's language (a Russian edition read in Russian): nothing to pay for
        if (sameLanguage(context.sourceLanguage, s.language.code)) return ProcessedPage(input)
        val provider = s.provider ?: throw NotConfiguredException()
        val size = PageBitmaps.size(input) ?: error("Not an image")
        // Translated before (the image was dropped from the cache, or the lettering changed): only draw
        val keptKey = translations?.let { TranslationStore.key(input, textSignature(s, provider)) }
        val kept = keptKey?.let { translations?.get(it) }
            ?.let { runCatching { json.decodeFromString(TranslatedPage.serializer(), it) }.getOrNull() }
        val page = kept ?: translate(input, size, context, s, provider).also { translated ->
            keptKey?.let { translations?.put(it, json.encodeToString(TranslatedPage.serializer(), translated)) }
        }
        val regions = page.regions

        // A few pages at a time: a long strip at full size takes tens of megabytes while it is drawn
        val image = rendering.withPermit {
            // Full size up to 16 MP whatever the shape (a 4096 side limit made 800 × 14 820 strips 200 px
            // wide and blurry); larger pages are decoded smaller, boxes are in original pixels
            val bitmap = PageBitmaps.decode(input, maxSide = MAX_RENDER_SIDE, maxPixels = MAX_RENDER_PIXELS)
            val scaled = if (bitmap.width == size.width) {
                regions
            } else {
                regions.scaledTo(bitmap.width.toFloat() / size.width)
            }
            PageTypesetter.render(bitmap, scaled, s.language.code, lettering)
            PageBitmaps.encode(bitmap, Bitmap.CompressFormat.JPEG, quality = 95).also { bitmap.recycle() }
        }

        return ProcessedPage(image = image, metadata = json.encodeToString(TranslatedPage.serializer(), page))
    }

    private suspend fun translate(
        input: ByteArray,
        size: PageBitmaps.Size,
        context: PageContext,
        s: Settings,
        provider: AiProviderConfig,
    ): TranslatedPage {
        val result = text.translate(
            client = clientFor(provider),
            images = modelImages(input, size),
            width = size.width,
            height = size.height,
            context = context,
            language = s.language,
            translateSoundEffects = s.translateSoundEffects,
            // Claude 4.7+ answers in pixels of the image (and errs when asked for 0–1000); Gemini in 0–1000
            units = if (provider.type == AiProviderType.Anthropic) BoxUnits.Pixels else BoxUnits.PerMille,
        )
        return TranslatedPage(
            language = s.language.code,
            regions = result.regions,
            model = provider.model,
            usage = result.usage,
            fixedLines = result.fixedLines,
        )
    }

    /** What changes a page's text (not how it is drawn): a kept translation is used while these stay. */
    private fun textSignature(s: Settings, provider: AiProviderConfig): String = listOf(
        "v${TranslationPrompt.VERSION}",
        provider.id,
        provider.model,
        s.language.code,
        "sfx=${s.translateSoundEffects}",
    ).joinToString("|")

    /** Translate again: the kept translation of this page goes, the model is asked anew. */
    override fun forget(input: ByteArray, context: PageContext) {
        val s = settings()
        val provider = s.provider ?: return
        translations?.remove(TranslationStore.key(input, textSignature(s, provider)))
    }

    /** Same language; Chinese only when the script matches too (Simplified and Traditional differ). */
    private fun sameLanguage(source: String?, target: String): Boolean {
        if (source == null) return false
        if (source.equals(target, ignoreCase = true)) return true
        val base = source.substringBefore('-').lowercase()
        return base != "zh" && base == target.substringBefore('-').lowercase()
    }

    /**
     * The page as the model gets it: one image, or for a long strip its parts at the page's own
     * width (see [StripParts]), each scaled down only if it is still too large.
     */
    private fun modelImages(input: ByteArray, size: PageBitmaps.Size): List<PageTextTranslator.ModelImage> {
        if (!StripParts.isStrip(size.width, size.height)) return listOf(imageForModel(input, size))
        val preview = PageBitmaps.decode(input, maxSide = PREVIEW_SIDE)
        val pixels = IntArray(preview.width * preview.height)
        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        val spread = StripParts.rowSpread(pixels, preview.width, preview.height)
        val rowScale = size.height.toDouble() / preview.height
        preview.recycle()
        val parts = StripParts.parts(size.width, size.height, spread, rowScale)
        val decoder = regionDecoder(input) ?: return listOf(imageForModel(input, size))
        try {
            return parts.map { rows ->
                val part = decoder.decodeRegion(Rect(0, rows.first, size.width, rows.last + 1), null)
                    ?: return listOf(imageForModel(input, size))
                val factor = minOf(
                    1f,
                    MAX_PART_SIDE.toFloat() / maxOf(part.width, part.height),
                    kotlin.math.sqrt(MAX_MODEL_PIXELS.toFloat() / (part.width.toLong() * part.height)),
                )
                val width = (part.width * factor).toInt().coerceAtLeast(1)
                val height = (part.height * factor).toInt().coerceAtLeast(1)
                val scaled = if (factor < 1f) Bitmap.createScaledBitmap(part, width, height, true) else part
                val bytes = PageBitmaps.encode(scaled, Bitmap.CompressFormat.JPEG, quality = 90)
                if (scaled !== part) scaled.recycle()
                part.recycle()
                PageTextTranslator.ModelImage(
                    AiImage(bytes, "image/jpeg"),
                    width,
                    height,
                    top = rows.first,
                    rows = rows.last + 1 - rows.first,
                )
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun regionDecoder(input: ByteArray): BitmapRegionDecoder? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(input, 0, input.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(input, 0, input.size, false)
        }
    } catch (e: IOException) {
        null
    }

    /**
     * The page as the model sees it. Pages of normal size go as they are (no quality loss); very
     * large ones are scaled down to save upload and tokens, and so that Claude doesn't scale them
     * itself (its pixel coordinates then match the image we know).
     */
    private fun imageForModel(input: ByteArray, size: PageBitmaps.Size): PageTextTranslator.ModelImage {
        val mime = PageBitmaps.mimeType(input)
        val supported = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"
        val factor = minOf(
            1f,
            MAX_MODEL_SIDE.toFloat() / maxOf(size.width, size.height),
            kotlin.math.sqrt(MAX_MODEL_PIXELS.toFloat() / (size.width.toLong() * size.height)),
        )
        if (supported && factor >= 1f && input.size <= MAX_MODEL_BYTES) {
            return PageTextTranslator.ModelImage(AiImage(input, mime), size.width, size.height)
        }
        val bitmap = PageBitmaps.decode(input, maxSide = MAX_MODEL_SIDE)
        val width = (size.width * factor).toInt().coerceAtLeast(1)
        val height = (size.height * factor).toInt().coerceAtLeast(1)
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val bytes = PageBitmaps.encode(scaled, Bitmap.CompressFormat.JPEG, quality = 90)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return PageTextTranslator.ModelImage(AiImage(bytes, "image/jpeg"), width, height)
    }

    private fun List<TextRegion>.scaledTo(factor: Float) = map { region ->
        region.copy(
            box = Box(
                (region.box.left * factor).toInt(),
                (region.box.top * factor).toInt(),
                (region.box.right * factor).toInt(),
                (region.box.bottom * factor).toInt(),
            ),
        )
    }

    private val rendering = Semaphore(RENDER_AT_ONCE)

    private companion object {
        const val MAX_MODEL_SIDE = 2048

        /** With more than 20 images in a request Claude takes at most 2000 px a side. */
        const val MAX_PART_SIDE = 2000

        /** The copy a strip's quiet rows are looked for in. */
        const val PREVIEW_SIDE = 4096
        const val MAX_RENDER_SIDE = 65_000
        const val MAX_RENDER_PIXELS = 16L * 1024 * 1024
        const val RENDER_AT_ONCE = 2

        /** Bump when erasing or typesetting changes: pages already made are then drawn again. */
        const val RENDER_VERSION = 10
        const val MAX_MODEL_BYTES = 4 * 1024 * 1024

        /** Claude scales larger images down itself (about 3.75 MP); keep below to know the size. */
        const val MAX_MODEL_PIXELS = 3_500_000
    }
}
