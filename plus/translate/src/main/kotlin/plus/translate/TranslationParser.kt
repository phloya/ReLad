package plus.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** How the model is asked to give box coordinates. */
enum class BoxUnits {
    /** `[ymin, xmin, ymax, xmax]` in 0–1000 of the image: Gemini's own way, also Qwen and most others. */
    PerMille,

    /**
     * `[x1, y1, x2, y2]` in pixels of the image the model got: Claude's own way (4.7 and later map
     * coordinates 1:1 to pixels). Asked for Gemini's order, it got confused and answered twice.
     */
    Pixels,
}

/** Reads the model's answer into regions in page pixels, forgiving the usual model sloppiness. */
object TranslationParser {

    class ParseException(message: String) : Exception(message)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A translated page: its text regions and what the model noticed for the title's notes. */
    class Page(val regions: List<TextRegion>, val findings: PageFindings)

    /**
     * A part of the page the model got as one image: page rows [top] until [top] + [rows], sent as an
     * image of [imageWidth] × [imageHeight]. A long strip goes in several parts (see [StripParts]).
     */
    class ImagePart(val top: Int, val rows: Int, val imageWidth: Int, val imageHeight: Int)

    fun parse(answer: String, pageWidth: Int, pageHeight: Int): List<TextRegion> =
        parsePage(answer, pageWidth, pageHeight).regions

    /**
     * [units] is what the prompt asked for; [imageWidth] × [imageHeight] is the image the model got
     * (smaller than the page when the page was scaled down for it). Numbers above 1000 can only be
     * pixels, so such answers are read as pixels whatever was asked: models fall back to their habit.
     */
    fun parsePage(
        answer: String,
        pageWidth: Int,
        pageHeight: Int,
        units: BoxUnits = BoxUnits.PerMille,
        imageWidth: Int = pageWidth,
        imageHeight: Int = pageHeight,
    ): Page = parseParts(answer, pageWidth, units, listOf(ImagePart(0, pageHeight, imageWidth, imageHeight)))

    /**
     * A page sent as [parts]: each region says which image it is in ("part", from 1; the first if it
     * doesn't) and its box is in that image. Text in two overlapping parts is kept once.
     */
    fun parseParts(answer: String, pageWidth: Int, units: BoxUnits, parts: List<ImagePart>): Page {
        val root = root(answer)
        val items = when (root) {
            is JsonObject -> root["regions"] as? JsonArray ?: JsonArray(emptyList())
            is JsonArray -> root
            else -> JsonArray(emptyList())
        }.mapNotNull { it as? JsonObject }
        val pixels = units == BoxUnits.Pixels ||
            items.any { item -> numbers(item.boxElement()).orEmpty().any { it > PER_MILLE } }
        val xFirst = units == BoxUnits.Pixels
        val found = items.mapNotNull { item ->
            val number = (item["part"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 1
            val index = number.coerceIn(1, parts.size) - 1
            val part = parts[index]
            val scale = if (pixels) {
                Scale(part.imageWidth.toDouble(), part.imageHeight.toDouble(), pageWidth, part.rows, xFirst)
            } else {
                Scale(PER_MILLE, PER_MILLE, pageWidth, part.rows, xFirst)
            }
            region(item, scale)?.let { region ->
                if (part.top == 0) {
                    region
                } else {
                    val box = region.box
                    region.copy(box = Box(box.left, box.top + part.top, box.right, box.bottom + part.top))
                }
            }
        }
        val regions = if (parts.size > 1) StripParts.withoutRepeats(found) else found
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

    private fun region(item: JsonObject, scale: Scale): TextRegion? {
        val translation = item.string("translation")?.trim().orEmpty()
        if (translation.isEmpty()) return null
        val box = box(item.boxElement(), scale) ?: return null
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

    /** The model's coordinate space ([fromWidth] × [fromHeight]) and the page's pixels. */
    private class Scale(
        val fromWidth: Double,
        val fromHeight: Double,
        val pageWidth: Int,
        val pageHeight: Int,
        /** `[x1, y1, x2, y2]` instead of `[ymin, xmin, ymax, xmax]`. */
        val xFirst: Boolean,
    )

    private fun JsonObject.boxElement(): JsonElement? = this["box"] ?: this["box_2d"] ?: this["bbox"]

    private fun numbers(element: JsonElement?): List<Double>? =
        (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }?.takeIf { it.size == 4 }

    /** A box in the model's space and order → page pixels. Swapped corners are put right. */
    private fun box(element: JsonElement?, scale: Scale): Box? {
        val values = numbers(element) ?: return null
        val (x1, y1, x2, y2) = if (scale.xFirst) values else listOf(values[1], values[0], values[3], values[2])
        fun x(v: Double) = (v.coerceIn(0.0, scale.fromWidth) / scale.fromWidth * scale.pageWidth).toInt()
        fun y(v: Double) = (v.coerceIn(0.0, scale.fromHeight) / scale.fromHeight * scale.pageHeight).toInt()
        val box = Box(
            left = x(minOf(x1, x2)),
            top = y(minOf(y1, y2)),
            right = x(maxOf(x1, x2)),
            bottom = y(maxOf(y1, y2)),
        )
        return box.takeIf { it.width >= MIN_SIDE && it.height >= MIN_SIDE }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private val UNSURE = setOf("unsure", "unknown", "none", "null", "?", "narrator")
    private const val PER_MILLE = 1000.0
    private const val MIN_SIDE = 4
}
