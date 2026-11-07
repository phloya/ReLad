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

    suspend fun translate(
        client: AiClient,
        image: AiImage,
        width: Int,
        height: Int,
        context: PageContext,
        language: TargetLanguage,
        translateSoundEffects: Boolean,
    ): Result {
        ChapterMemory.started(context.chapterId, context.pageIndex)
        try {
            return run(client, image, width, height, context, language, translateSoundEffects)
        } finally {
            ChapterMemory.finished(context.chapterId, context.pageIndex)
        }
    }

    private suspend fun run(
        client: AiClient,
        image: AiImage,
        width: Int,
        height: Int,
        context: PageContext,
        language: TargetLanguage,
        translateSoundEffects: Boolean,
    ): Result {
        val bible = bibles.get(context.mangaId, context.mangaTitle, language.code, context.sourceLanguage)
            .withAbout(context)
        val previous = ChapterMemory.previous(context.chapterId, context.pageIndex)
        val rules = TranslationPrompt.rules(language, context.sourceLanguage, translateSoundEffects)
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
                ),
                images = listOf(image),
                jsonOutput = true,
                // A page is careful writing, not a puzzle: deep hidden reasoning only adds cost
                effort = AiEffort.Low,
            ),
        )
        response.usage?.let(usage::add)
        val answer = response.text ?: throw AiException("The model sent no text", AiException.Kind.BadResponse)
        val page = TranslationParser.parsePage(answer, width, height)
        var regions = page.regions.filter { translateSoundEffects || it.type.erasesOriginal }

        // Free checks on the phone; only flagged lines go back for a second look, as text
        val problems = TranslationChecks.check(regions, bible)
        if (problems.isNotEmpty()) {
            try {
                val fix = client.send(
                    AiRequest(
                        stableSystem = rules,
                        system = notes,
                        prompt = TranslationPrompt.fixes(regions, problems),
                        jsonOutput = true,
                        maxOutputTokens = FIX_MAX_TOKENS,
                        effort = AiEffort.Low,
                    ),
                )
                fix.usage?.let(usage::add)
                val fixes = fix.text?.let(TranslationParser::parseFixes).orEmpty()
                regions = regions.mapIndexed { index, region ->
                    fixes[index]?.let { region.copy(translation = it) } ?: region
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
        const val MAX_GENRES = 12
    }
}

/**
 * Lines of the last pages of recent chapters, given to the model as context (original and
 * translation, with speakers). Kept in memory only: the title's notes carry what must last.
 *
 * Pages are translated two at a time and the newest request first, so the previous page may still
 * be on its way: a page then waits for it a little, to get its lines.
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

    suspend fun previous(chapterId: Long, pageIndex: Int): List<TextRegion> {
        val waiting = synchronized(this) { inFlight[chapterId to (pageIndex - 1)] }
        if (waiting != null) withTimeoutOrNull(WAIT_FOR_PREVIOUS_MS) { waiting.await() }
        return synchronized(this) {
            val pages = chapters[chapterId] ?: return emptyList()
            pages.filterKeys { it < pageIndex }.toSortedMap().values.toList().takeLast(CONTEXT_PAGES).flatten()
        }
    }

    private const val MAX_CHAPTERS = 20
    private const val CONTEXT_PAGES = 2
    private const val WAIT_FOR_PREVIOUS_MS = 15_000L
}
