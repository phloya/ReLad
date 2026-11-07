package plus.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import plus.core.ai.AiJson
import plus.translate.bible.Alias
import plus.translate.bible.Character
import plus.translate.bible.Gender
import plus.translate.bible.PageFindings
import plus.translate.bible.Term
import plus.translate.bible.TermKind

/** Reads the model's answer into regions in page pixels, forgiving the usual model sloppiness. */
object TranslationParser {

    class ParseException(message: String) : Exception(message)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A translated page: its text regions and what the model noticed for the title's notes. */
    class Page(val regions: List<TextRegion>, val findings: PageFindings)

    fun parse(answer: String, pageWidth: Int, pageHeight: Int): List<TextRegion> =
        parsePage(answer, pageWidth, pageHeight).regions

    fun parsePage(answer: String, pageWidth: Int, pageHeight: Int): Page {
        val root = root(answer)
        val items = when (root) {
            is JsonObject -> root["regions"] as? JsonArray ?: JsonArray(emptyList())
            is JsonArray -> root
            else -> JsonArray(emptyList())
        }
        val regions = items.mapNotNull { (it as? JsonObject)?.let { item -> region(item, pageWidth, pageHeight) } }
        val obj = root as? JsonObject
        val findings = PageFindings(
            characters = obj.list("new_characters").mapNotNull(::character),
            terms = obj.list("new_terms").mapNotNull(::term),
            summary = obj?.string("summary").orEmpty(),
            lines = regions.map { it.text },
            speakers = regions.mapNotNull { it.speaker },
        )
        return Page(regions, findings)
    }

    /** Corrected lines from a second look: line number → new translation. */
    fun parseFixes(answer: String): Map<Int, String> {
        val obj = root(answer) as? JsonObject ?: return emptyMap()
        return obj.list("fixes").mapNotNull { item ->
            val id = item["id"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@mapNotNull null
            val translation = item.string("translation")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to translation
        }.toMap()
    }

    private fun root(answer: String): JsonElement {
        val jsonText = AiJson.extract(answer) ?: throw ParseException("No JSON in the answer")
        return try {
            json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw ParseException("Broken JSON in the answer: ${e.message}")
        }
    }

    private fun character(item: JsonObject): Character? {
        val original = item.string("original")?.trim().orEmpty()
        val name = (item.string("name") ?: item.string("translation"))?.trim().orEmpty()
        if (original.isEmpty() || name.isEmpty()) return null
        val gender = when (item.string("gender")?.lowercase()) {
            "male", "m", "man", "boy" -> Gender.Male
            "female", "f", "woman", "girl" -> Gender.Female
            "other", "nonbinary" -> Gender.Other
            else -> Gender.Unknown
        }
        return Character(
            original = original,
            name = name,
            gender = gender,
            speech = item.string("speech").orEmpty(),
            addresses = item.string("addresses").orEmpty(),
            notes = item.string("notes").orEmpty(),
            aliases = (item["aliases"] as? JsonArray).orEmpty().mapNotNull { alias ->
                val a = alias as? JsonObject ?: return@mapNotNull null
                val source = a.string("original") ?: return@mapNotNull null
                val target = a.string("translation") ?: a.string("name") ?: return@mapNotNull null
                Alias(source, target)
            },
        )
    }

    private fun term(item: JsonObject): Term? {
        val original = item.string("original")?.trim().orEmpty()
        val translation = item.string("translation")?.trim().orEmpty()
        if (original.isEmpty() || translation.isEmpty()) return null
        val kind = TermKind.entries.firstOrNull { it.name.equals(item.string("kind")?.trim(), ignoreCase = true) }
            ?: TermKind.Other
        return Term(original, translation, kind, item.string("note").orEmpty())
    }

    private fun JsonObject?.list(key: String): List<JsonObject> =
        (this?.get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun region(item: JsonObject, pageWidth: Int, pageHeight: Int): TextRegion? {
        val translation = item.string("translation")?.trim().orEmpty()
        if (translation.isEmpty()) return null
        val box = box(item["box"] ?: item["box_2d"] ?: item["bbox"], pageWidth, pageHeight) ?: return null
        val type = when (item.string("type")?.lowercase()) {
            "bubble", "speech", "thought" -> RegionType.Bubble
            "caption", "narration" -> RegionType.Caption
            "sfx", "sound", "sound_effect" -> RegionType.Sfx
            else -> RegionType.Other
        }
        val speaker = item.string("speaker")?.trim()?.takeIf {
            it.isNotEmpty() && it.lowercase() !in UNSURE
        }
        return TextRegion(type, box, item.string("text").orEmpty(), translation, speaker)
    }

    /** `[ymin, xmin, ymax, xmax]` in 0–1000 → pixels. Swapped corners are put right. */
    private fun box(element: JsonElement?, pageWidth: Int, pageHeight: Int): Box? {
        val values = (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: return null
        if (values.size != 4) return null
        val (y1, x1, y2, x2) = values.map { it.coerceIn(0.0, SCALE) }
        val box = Box(
            left = (minOf(x1, x2) / SCALE * pageWidth).toInt(),
            top = (minOf(y1, y2) / SCALE * pageHeight).toInt(),
            right = (maxOf(x1, x2) / SCALE * pageWidth).toInt(),
            bottom = (maxOf(y1, y2) / SCALE * pageHeight).toInt(),
        )
        return box.takeIf { it.width >= MIN_SIDE && it.height >= MIN_SIDE }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private val UNSURE = setOf("unsure", "unknown", "none", "null", "?", "narrator")
    private const val SCALE = 1000.0
    private const val MIN_SIDE = 4
}
