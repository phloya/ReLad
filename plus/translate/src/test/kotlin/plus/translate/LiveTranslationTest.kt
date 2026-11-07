package plus.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import plus.core.ai.AiClient
import plus.core.ai.AiClients
import plus.core.ai.AiImage
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiProviderType
import plus.core.ai.AiRequest
import plus.core.ai.AiResponse
import plus.core.pipeline.PageContext
import plus.translate.bible.BibleStore
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Translates real chapters with a real model, to judge quality and measure cost. Never runs on its
 * own: it spends money. Run by hand (the key is read from a file, never from the command line):
 *
 *     RELAD_LIVE_PAGES="/path/Chapter 1:/path/Chapter 2" RELAD_LIVE_OUT=/tmp/out \
 *       ./gradlew :plus:translate:testDebugUnitTest --tests '*LiveTranslationTest*' --rerun
 *
 * Optional: RELAD_LIVE_KEY_FILE (default ~/.relad/anthropic.key), RELAD_LIVE_MODEL (claude-sonnet-5-5),
 * RELAD_LIVE_LANGUAGE (en), RELAD_LIVE_SOURCE (source language code), RELAD_LIVE_LIMIT (pages per chapter),
 * RELAD_LIVE_TITLE, RELAD_LIVE_PARALLEL (pages at once, as the app does; default 1). Writes each page's
 * lines and usage, the title's notes and a summary into RELAD_LIVE_OUT.
 */
@EnabledIfEnvironmentVariable(named = "RELAD_LIVE_PAGES", matches = ".+")
class LiveTranslationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `translate real chapters`() = runBlocking(Dispatchers.IO) {
        val env = System.getenv()
        val chapters = env.getValue("RELAD_LIVE_PAGES").split(':').map(::File)
        val out = File(env.getValue("RELAD_LIVE_OUT")).apply { mkdirs() }
        val keyFile = File(env["RELAD_LIVE_KEY_FILE"] ?: "${System.getProperty("user.home")}/.relad/anthropic.key")
        val model = env["RELAD_LIVE_MODEL"] ?: "claude-sonnet-5-5"
        val language = TargetLanguage.of(env["RELAD_LIVE_LANGUAGE"] ?: "en")
        val source = env["RELAD_LIVE_SOURCE"]
        val limit = env["RELAD_LIVE_LIMIT"]?.toInt() ?: Int.MAX_VALUE
        val title = env["RELAD_LIVE_TITLE"] ?: chapters.first().parentFile?.name ?: "Test"
        val parallel = env["RELAD_LIVE_PARALLEL"]?.toInt() ?: 1

        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()
        val provider = AiProviderConfig(
            id = "live",
            name = "Live test",
            type = AiProviderType.Anthropic,
            baseUrl = AiProviderType.Anthropic.defaultBaseUrl,
            model = model,
        )
        val real = AiClients.create(provider, keyFile.readText().trim(), http)

        // Keeps every raw answer next to the page, to see what the parser made of it
        fun clientFor(name: String) = object : AiClient {
            var call = 0
            override suspend fun send(request: AiRequest): AiResponse = real.send(request).also { response ->
                File(out, "raw").mkdirs()
                File(out, "raw/$name-${call++}.txt").writeText(response.text.orEmpty())
            }
        }
        val bibles = BibleStore(File(out, "bible"))
        val translator = PageTextTranslator(bibles)
        val lines = sortedMapOf<String, String>()
        var input = 0L
        var cached = 0L
        var written = 0L
        var output = 0L
        val lock = Mutex()
        val gate = Semaphore(parallel)
        val runStarted = System.currentTimeMillis()

        suspend fun note(name: String, line: String) = lock.withLock {
            lines[name] = line
            File(out, "summary.tsv").writeText(lines.values.joinToString(""))
        }

        coroutineScope {
            chapters.forEachIndexed { chapterIndex, dir ->
                val pages = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }!!
                    .sortedBy { it.name }
                    .take(limit)
                pages.forEachIndexed { pageIndex, file ->
                    val name = "${dir.name}-${file.nameWithoutExtension}"
                    // In page order, at most [parallel] at once, as the app's queue does
                    gate.acquire()
                    launch {
                        try {
                            val bytes = file.readBytes()
                            val image = ImageIO.read(file)
                            val mime = if (file.extension.lowercase() == "png") "image/png" else "image/jpeg"
                            val started = System.currentTimeMillis()
                            val result = try {
                                translator.translate(
                                    client = clientFor(name),
                                    image = PageTextTranslator.ModelImage(
                                        AiImage(bytes, mime),
                                        image.width,
                                        image.height,
                                    ),
                                    width = image.width,
                                    height = image.height,
                                    context = PageContext(
                                        mangaId = 1,
                                        mangaTitle = title,
                                        chapterId = chapterIndex + 1L,
                                        chapterName = dir.name,
                                        pageIndex = pageIndex,
                                        sourceLanguage = source,
                                    ),
                                    language = language,
                                    translateSoundEffects = true,
                                    units = BoxUnits.Pixels,
                                )
                            } catch (e: Exception) {
                                // One bad page must not stop the run: note it and go on
                                note(name, "$name\tFAILED: ${e.message?.take(200)}\n")
                                return@launch
                            }
                            val seconds = (System.currentTimeMillis() - started) / 1000.0
                            File(out, "$name.json").writeText(
                                json.encodeToString(
                                    TranslatedPage.serializer(),
                                    TranslatedPage(
                                        language.code,
                                        result.regions,
                                        model,
                                        result.usage,
                                        result.fixedLines,
                                    ),
                                ),
                            )
                            lock.withLock {
                                result.usage?.let {
                                    input += it.inputTokens
                                    cached += it.cachedInputTokens
                                    written += it.cacheWriteTokens
                                    output += it.outputTokens
                                }
                            }
                            note(
                                name,
                                "$name\t${result.regions.size} lines\t${result.fixedLines} fixed\t" +
                                    "in=${result.usage?.inputTokens} cached=${result.usage?.cachedInputTokens} " +
                                    "out=${result.usage?.outputTokens}\t${seconds}s\n",
                            )
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        }
        val summary = StringBuilder(lines.values.joinToString(""))
        summary.append("\nWALL ${(System.currentTimeMillis() - runStarted) / 1000}s, $parallel at once\n")
        summary.append("\nTOTAL input=$input (cached read $cached, cache write $written) output=$output\n")
        File(out, "summary.tsv").writeText(summary.toString())
    }
}
