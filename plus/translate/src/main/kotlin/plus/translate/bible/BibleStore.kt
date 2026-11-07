package plus.translate.bible

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps each title's notes as a JSON file per target language in [directory]. Reads are served
 * from memory after the first; changes are written right away (to a temporary file first, so a
 * crash never leaves half a file). Pages translated at the same time update one title one by one.
 */
class BibleStore(private val directory: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private val mutex = Mutex()
    private val loaded = HashMap<String, TitleBible>()

    /** The notes, or new empty ones when the title has none in this language yet. */
    suspend fun get(mangaId: Long, mangaTitle: String, language: String, sourceLanguage: String?): TitleBible =
        mutex.withLock { read(mangaId, language) ?: TitleBible(mangaId, mangaTitle, language, sourceLanguage) }

    /** Applies [change] to the current notes and saves the result if it differs. */
    suspend fun update(
        mangaId: Long,
        mangaTitle: String,
        language: String,
        sourceLanguage: String?,
        change: (TitleBible) -> TitleBible,
    ): TitleBible = mutex.withLock {
        val current = read(mangaId, language) ?: TitleBible(mangaId, mangaTitle, language, sourceLanguage)
        val changed = change(current)
        if (changed != current) write(changed)
        changed
    }

    /** Replaces the notes with what the reader edited or imported. */
    suspend fun put(bible: TitleBible) = mutex.withLock { write(bible) }

    suspend fun delete(mangaId: Long, language: String) = mutex.withLock {
        loaded.remove(name(mangaId, language))
        file(mangaId, language).delete()
    }

    fun export(bible: TitleBible): String = json.encodeToString(TitleBible.serializer(), bible)

    fun import(text: String): TitleBible = json.decodeFromString(TitleBible.serializer(), text)

    private fun read(mangaId: Long, language: String): TitleBible? {
        val name = name(mangaId, language)
        loaded[name]?.let { return it }
        val file = file(mangaId, language)
        if (!file.exists()) return null
        val bible = try {
            json.decodeFromString(TitleBible.serializer(), file.readText())
        } catch (e: Exception) {
            // A broken file must not stop translation: keep it aside for a look and start over
            file.renameTo(File(directory, "$name.broken-${System.currentTimeMillis()}"))
            return null
        }
        loaded[name] = bible
        return bible
    }

    private fun write(bible: TitleBible) {
        directory.mkdirs()
        val name = name(bible.mangaId, bible.language)
        val target = file(bible.mangaId, bible.language)
        val temp = File(directory, "$name.tmp")
        temp.writeText(export(bible))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        loaded[name] = bible
    }

    private fun file(mangaId: Long, language: String) = File(directory, "${name(mangaId, language)}.json")

    private fun name(mangaId: Long, language: String) = "$mangaId-${language.filter {
        it.isLetterOrDigit() || it == '-'
    }}"
}
