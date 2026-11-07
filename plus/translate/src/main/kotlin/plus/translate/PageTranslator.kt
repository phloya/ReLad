package plus.translate

import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import plus.core.ai.AiClient
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
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
     * The page as the model sees it. Pages of normal size go as they are (no quality loss);
     * very large ones are scaled down to save upload and tokens. Box coordinates are relative
     * (0–1000), so the scale does not matter for them.
     */
    private fun imageForModel(input: ByteArray, size: PageBitmaps.Size): AiImage {
        val mime = PageBitmaps.mimeType(input)
        val supported = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"
        if (supported && maxOf(size.width, size.height) <= MAX_MODEL_SIDE && input.size <= MAX_MODEL_BYTES) {
            return AiImage(input, mime)
        }
        val bitmap = PageBitmaps.decode(input, maxSide = MAX_MODEL_SIDE)
        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_MODEL_SIDE) {
            val factor = MAX_MODEL_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
        } else {
            bitmap
        }
        val bytes = PageBitmaps.encode(scaled, Bitmap.CompressFormat.JPEG, quality = 90)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return AiImage(bytes, "image/jpeg")
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
        const val MAX_MODEL_BYTES = 4 * 1024 * 1024
    }
}
