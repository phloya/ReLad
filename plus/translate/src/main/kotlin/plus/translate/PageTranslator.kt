package plus.translate

import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import plus.core.ai.AiClient
import plus.core.ai.AiException
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiRequest
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage

/**
 * The translation step of the page pipeline: one request to a multimodal model finds and
 * translates all text on the page, then [PageTypesetter] draws the result onto the page.
 */
class PageTranslator(
    private val settings: () -> Settings,
    private val clientFor: (AiProviderConfig) -> AiClient,
) : PageProcessor {

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
        val provider = s.provider ?: throw NotConfiguredException()
        val size = PageBitmaps.size(input) ?: error("Not an image")

        val response = clientFor(provider).send(
            AiRequest(
                system = TranslationPrompt.system(s.language),
                prompt = TranslationPrompt.user(ChapterMemory.previous(context.chapterId, context.pageIndex)),
                images = listOf(imageForModel(input, size)),
                jsonOutput = true,
            ),
        )
        val answer = response.text ?: throw AiException("The model sent no text", AiException.Kind.BadResponse)
        val regions = TranslationParser.parse(answer, size.width, size.height)
            .filter { s.translateSoundEffects || it.type.erasesOriginal }
        ChapterMemory.remember(context.chapterId, context.pageIndex, regions)

        val bitmap = PageBitmaps.decode(input)
        // Very large pages are decoded smaller; boxes are in original pixels
        val scaled = if (bitmap.width == size.width) regions else regions.scaledTo(bitmap.width.toFloat() / size.width)
        PageTypesetter.render(bitmap, scaled)
        val image = PageBitmaps.encode(bitmap, Bitmap.CompressFormat.JPEG, quality = 95)
        bitmap.recycle()

        return ProcessedPage(
            image = image,
            metadata = json.encodeToString(TranslatedPage(s.language.code, regions)),
        )
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

/**
 * Translations of the last pages of recent chapters, given to the model as context so names and
 * terms stay the same from page to page. Kept in memory only; a glossary will replace it later.
 */
internal object ChapterMemory {

    private val chapters = object : LinkedHashMap<Long, MutableMap<Int, List<TextRegion>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableMap<Int, List<TextRegion>>>) =
            size > MAX_CHAPTERS
    }

    @Synchronized
    fun remember(chapterId: Long, pageIndex: Int, regions: List<TextRegion>) {
        chapters.getOrPut(chapterId) { mutableMapOf() }[pageIndex] = regions
    }

    @Synchronized
    fun previous(chapterId: Long, pageIndex: Int): List<TextRegion> {
        val pages = chapters[chapterId] ?: return emptyList()
        return pages.filterKeys { it < pageIndex }.toSortedMap().values.toList().takeLast(CONTEXT_PAGES).flatten()
    }

    private const val MAX_CHAPTERS = 20
    private const val CONTEXT_PAGES = 2
}
