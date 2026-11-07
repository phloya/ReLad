package plus.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import plus.core.ai.AiClient
import plus.core.ai.AiEffort
import plus.core.ai.AiException
import plus.core.ai.AiImage
import plus.core.ai.AiRequest
import plus.core.ai.AiUsage
import plus.core.pipeline.PageContext
import plus.translate.bible.BibleStore
import plus.translate.bible.BibleUpdates
import plus.translate.bible.TitleBible
import plus.translate.bible.TranslationChecks

/**
 * The text side of translating a page, without drawing: the request with the title's notes, the
 * checks and the second look, and adding what the model found to the notes. See plus/TRANSLATION.md.
 */
internal class PageTextTranslator(private val bibles: BibleStore) {

    class Result(val regions: List<TextRegion>, val usage: PageUsage?, val fixedLines: Int)

    /**
     * An image as the model gets it, with its size (smaller than the page when scaled down). A long
     * strip goes as several, each showing page rows [top] until [top] + [rows]; `null` rows: all.
     */
    class ModelImage(val image: AiImage, val width: Int, val height: Int, val top: Int = 0, val rows: Int? = null)

    /** One image of the whole page. */
    suspend fun translate(
        client: AiClient,
        image: ModelImage,
        width: Int,
        height: Int,
        context: PageContext,
        language: TargetLanguage,
        translateSoundEffects: Boolean,
        units: BoxUnits = BoxUnits.PerMille,
    ): Result = translate(client, listOf(image), width, height, context, language, translateSoundEffects, units)

    /**
     * [width] × [height] is the page; boxes come back in its pixels. [units]: how the model is asked
     * for coordinates (Claude: pixels, others: 0–1000). [images]: the page, or its parts from top to bottom.
     */
    suspend fun translate(
        client: AiClient,
        images: List<ModelImage>,
        width: Int,
        height: Int,
        context: PageContext,
        language: TargetLanguage,
        translateSoundEffects: Boolean,
        units: BoxUnits = BoxUnits.PerMille,
    ): Result {
        ChapterMemory.started(context.chapterId, context.pageIndex)
        try {
            return run(client, images, width, height, context, language, translateSoundEffects, units)
        } finally {
            ChapterMemory.finished(context.chapterId, context.pageIndex)
        }
    }

    private suspend fun run(
        client: AiClient,
        images: List<ModelImage>,
        width: Int,
        height: Int,
        context: PageContext,
        language: TargetLanguage,
        translateSoundEffects: Boolean,
        units: BoxUnits,
    ): Result {
        var bible = bibles.get(context.mangaId, context.mangaTitle, language.code, context.sourceLanguage)
        // A title's first page goes alone: its names and how characters address each other are found
        // there. The pages after it wait for it (not each for the one before: that made the first six
        // pages a queue) and then go side by side with its notes; the notes keep names the same
        if (bible.isNew && ChapterMemory.awaitFirst(context.chapterId, context.pageIndex)) {
            bible = bibles.get(context.mangaId, context.mangaTitle, language.code, context.sourceLanguage)
        }
        bible = bible.withAbout(context)
        val previous = ChapterMemory.previous(context.chapterId, context.pageIndex)
        val rules = TranslationPrompt.rules(language, context.sourceLanguage, translateSoundEffects, units)
        val notes = TranslationPrompt.titleNotes(bible, context.chapterId)
        val usage = mutableListOf<AiUsage>()

        val response = client.send(
            AiRequest(
                stableSystem = rules,
                system = notes,
                prompt = TranslationPrompt.page(
                    bible,
                    context.chapterId,
                    context.chapterName,
                    context.pageIndex,
                    previous,
                    imageSizes = images.map { it.width to it.height },
                    pixels = units == BoxUnits.Pixels,
                ),
                images = images.map { it.image },
                jsonOutput = true,
                // Some reasoning, not none: without it the answer comes 2–3 times sooner, but the
                // boxes go astray (Haiku on Berserk: median overlap with the right place 0.59 against
                // 0.84 between two runs with it), and text is then left unerased and set off its place
                effort = AiEffort.Low,
            ),
        )
        response.usage?.let(usage::add)
        val answer = response.text ?: throw AiException("The model sent no text", AiException.Kind.BadResponse)
        val parts = images.map { TranslationParser.ImagePart(it.top, it.rows ?: height, it.width, it.height) }
        val page = TranslationParser.parseParts(answer, width, units, parts)
        // A model that won't translate a page often says so in its page notes instead of refusing
        // outright: that is a refusal (shown to the reader), not a page without text, and no note
        if (page.regions.isEmpty() && Refusals.looksLike(page.findings.summary)) {
            throw AiException(page.findings.summary.trim().take(MAX_REFUSAL), AiException.Kind.Refused)
        }
        var regions = page.regions
            .filter { translateSoundEffects || it.type.erasesOriginal }
            .map { it.copy(translation = Orthography.fix(language.code, it.translation)) }

        // Free checks on the phone; only flagged lines go back for a second look, as text. Against the
        // notes as they are now: pages done meanwhile may have added a name this page spelled otherwise
        val latest = bibles.get(context.mangaId, context.mangaTitle, language.code, context.sourceLanguage)
        val problems = TranslationChecks.check(regions, latest)
        if (problems.isNotEmpty()) {
            try {
                val fix = client.send(
                    AiRequest(
                        stableSystem = rules,
                        system = notes,
                        prompt = TranslationPrompt.fixes(regions, problems),
                        jsonOutput = true,
                        maxOutputTokens = FIX_MAX_TOKENS,
                        // Text only, no boxes: no reasoning needed
                        effort = AiEffort.None,
                    ),
                )
                fix.usage?.let(usage::add)
                val fixes = fix.text?.let(TranslationParser::parseFixes).orEmpty()
                regions = regions.mapIndexed { index, region ->
                    fixes[index]?.let { region.copy(translation = Orthography.fix(language.code, it)) } ?: region
                }
            } catch (e: AiException) {
                // The first translation is still good enough to show
            } catch (e: TranslationParser.ParseException) {
                // Same
            }
        }

        bibles.update(context.mangaId, context.mangaTitle, language.code, context.sourceLanguage) { current ->
            BibleUpdates.apply(
                current.withAbout(context),
                page.findings.copy(lines = regions.map { it.text }, speakers = regions.mapNotNull { it.speaker }),
                context.chapterId,
                context.chapterName,
                context.pageIndex,
            )
        }
        ChapterMemory.remember(context.chapterId, context.pageIndex, regions)
        return Result(regions, PageUsage.of(usage), problems.size)
    }

    /** The source's genres and description, for a title that has none in its notes yet. */
    private fun TitleBible.withAbout(context: PageContext): TitleBible {
        if (about.genres.isNotEmpty() || about.synopsis.isNotBlank()) return this
        val synopsis = context.mangaDescription?.trim().orEmpty()
        if (context.mangaGenres.isEmpty() && synopsis.isEmpty()) return this
        return copy(about = about.copy(genres = context.mangaGenres.take(MAX_GENRES), synopsis = synopsis))
    }

    private companion object {
        const val FIX_MAX_TOKENS = 2048

        /** A refusal is shown to the reader: its first sentences are enough. */
        const val MAX_REFUSAL = 300
        const val MAX_GENRES = 12
    }
}

/**
 * Lines of the last pages of recent chapters, given to the model as context (original and
 * translation, with speakers). Kept in memory only: the title's notes carry what must last.
 *
 * Pages are translated several at a time, so the previous page may still be on its way. While a
 * title is new a page waits for the first page on its way, to get its notes and lines; later it
 * takes the pages done.
 */
internal object ChapterMemory {

    private val chapters = object : LinkedHashMap<Long, MutableMap<Int, List<TextRegion>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableMap<Int, List<TextRegion>>>) =
            size > MAX_CHAPTERS
    }
    private val inFlight = HashMap<Pair<Long, Int>, CompletableDeferred<Unit>>()

    @Synchronized
    fun started(chapterId: Long, pageIndex: Int) {
        inFlight.getOrPut(chapterId to pageIndex) { CompletableDeferred() }
    }

    @Synchronized
    fun finished(chapterId: Long, pageIndex: Int) {
        inFlight.remove(chapterId to pageIndex)?.complete(Unit)
    }

    @Synchronized
    fun remember(chapterId: Long, pageIndex: Int, regions: List<TextRegion>) {
        chapters.getOrPut(chapterId) { mutableMapOf() }[pageIndex] = regions
    }

    /**
     * Waits (a little) for the earliest page of this chapter before [pageIndex] still on its way:
     * the one whose notes the others want. `true` if there was one.
     */
    suspend fun awaitFirst(chapterId: Long, pageIndex: Int): Boolean {
        val first = synchronized(this) {
            inFlight.entries
                .filter { (key, _) -> key.first == chapterId && key.second < pageIndex }
                .minByOrNull { (key, _) -> key.second }
                ?.value
        } ?: return false
        withTimeoutOrNull(WAIT_FOR_FIRST_MS) { first.await() }
        return true
    }

    fun previous(chapterId: Long, pageIndex: Int): List<TextRegion> {
        return synchronized(this) {
            val pages = chapters[chapterId] ?: return emptyList()
            pages.filterKeys { it < pageIndex }.toSortedMap().values.toList().takeLast(CONTEXT_PAGES).flatten()
        }
    }

    private const val MAX_CHAPTERS = 20
    private const val CONTEXT_PAGES = 2
    private const val WAIT_FOR_FIRST_MS = 30_000L
}
