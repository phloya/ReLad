package plus.translate

import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import plus.core.ai.AiClient
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiProviderType
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage
import plus.translate.bible.BibleStore

/**
 * The translation step of the page pipeline: one request to a multimodal model finds and
 * translates all text on the page, then [PageTypesetter] draws the result onto the page.
 */
class PageTranslator(
    private val settings: () -> Settings,
    private val clientFor: (AiProviderConfig) -> AiClient,
    /** Each title's notes: characters, glossary, story so far. */
    bibles: BibleStore,
) : PageProcessor {

    private val text = PageTextTranslator(bibles)

    class Settings(
        /** `null` when no provider is chosen yet: pages then fail with a message saying so. */
        val provider: AiProviderConfig?,
        val language: TargetLanguage,
        val translateSoundEffects: Boolean,
    )

    class NotConfiguredException : Exception("Choose an AI provider in Re:Lad → Page translation")

    override val id = "translate"
    override val order = 300

    private val json = Json

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
        val result = text.translate(
            client = clientFor(provider),
            image = imageForModel(input, size),
            width = size.width,
            height = size.height,
            context = context,
            language = s.language,
            translateSoundEffects = s.translateSoundEffects,
            // Claude 4.7+ answers in pixels of the image (and errs when asked for 0–1000); Gemini in 0–1000
            units = if (provider.type == AiProviderType.Anthropic) BoxUnits.Pixels else BoxUnits.PerMille,
        )
        val regions = result.regions

        val bitmap = PageBitmaps.decode(input)
        // Very large pages are decoded smaller; boxes are in original pixels
        val scaled = if (bitmap.width == size.width) regions else regions.scaledTo(bitmap.width.toFloat() / size.width)
        PageTypesetter.render(bitmap, scaled)
        val image = PageBitmaps.encode(bitmap, Bitmap.CompressFormat.JPEG, quality = 95)
        bitmap.recycle()

        return ProcessedPage(
            image = image,
            metadata = json.encodeToString(
                TranslatedPage(
                    language = s.language.code,
                    regions = regions,
                    model = provider.model,
                    usage = result.usage,
                    fixedLines = result.fixedLines,
                ),
            ),
        )
    }

    /** Same language; Chinese only when the script matches too (Simplified and Traditional differ). */
    private fun sameLanguage(source: String?, target: String): Boolean {
        if (source == null) return false
        if (source.equals(target, ignoreCase = true)) return true
        val base = source.substringBefore('-').lowercase()
        return base != "zh" && base == target.substringBefore('-').lowercase()
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

    private companion object {
        const val MAX_MODEL_SIDE = 2048

        /** Bump when erasing or typesetting changes: pages already made are then drawn again. */
        const val RENDER_VERSION = 4
        const val MAX_MODEL_BYTES = 4 * 1024 * 1024

        /** Claude scales larger images down itself (about 3.75 MP); keep below to know the size. */
        const val MAX_MODEL_PIXELS = 3_500_000
    }
}
