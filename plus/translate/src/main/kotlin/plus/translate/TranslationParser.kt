package plus.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import plus.core.ai.AiJson

/** Reads the model's answer into regions in page pixels, forgiving the usual model sloppiness. */
object TranslationParser {

    class ParseException(message: String) : Exception(message)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(answer: String, pageWidth: Int, pageHeight: Int): List<TextRegion> {
        val jsonText = AiJson.extract(answer) ?: throw ParseException("No JSON in the answer")
        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw ParseException("Broken JSON in the answer: ${e.message}")
        }
        val items = when (root) {
            is JsonObject -> root["regions"] as? JsonArray ?: JsonArray(emptyList())
            is JsonArray -> root
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { (it as? JsonObject)?.let { item -> region(item, pageWidth, pageHeight) } }
    }

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
        return TextRegion(type, box, item.string("text").orEmpty(), translation)
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

    private const val SCALE = 1000.0
    private const val MIN_SIDE = 4
}
