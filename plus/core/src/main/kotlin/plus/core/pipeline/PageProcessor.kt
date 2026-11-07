package plus.core.pipeline

/**
 * One step of Re:Lad's page pipeline: takes a page image and returns a new one
 * (translated, enhanced, colorized...). Processors run in [order], each on the previous one's output.
 */
interface PageProcessor {

    /** Stable id, part of the cache key. Never reuse an id for different output. */
    val id: String

    /** Lower runs first. Colorize (100) → enhance (200) → translate (300). */
    val order: Int

    /**
     * Describes everything that changes this processor's output for the same input: settings,
     * model, target language, prompt version. `null` means the processor is switched off.
     * A different signature means a different cache entry, so bump it when the output changes.
     */
    fun signature(context: PageContext): String?

    /**
     * Turns [input] (an encoded image) into a new encoded image. Called on a background thread,
     * may be slow (network, GPU). Throw to report a failure; the page then stays as it was.
     */
    suspend fun process(input: ByteArray, context: PageContext): ProcessedPage
}

/** What a processor produced: the encoded image and optional data about it (e.g. text regions as JSON). */
class ProcessedPage(
    val image: ByteArray,
    val metadata: String? = null,
)

/** What the pipeline knows about the page being processed. */
data class PageContext(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val pageIndex: Int,
    /** Language of the source the manga comes from (e.g. "ja"), if known. "all" or `null` when unknown. */
    val sourceLanguage: String?,
    /** The source's description of the manga, for translators to know what it is about. */
    val mangaDescription: String? = null,
    val mangaGenres: List<String> = emptyList(),
)
