package plus.translate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Puts the model's regions onto the text found on the page ([TextFinder]), so that a box drawn
 * roughly (too big, too small, beside its bubble) still ends up on its letters. The model is
 * trusted for what the text says and about where it is; the pixels for exactly where.
 *
 * 1. Each line of letters goes to the region whose box holds most of it.
 * 2. A region that took far more text than it reads gives back the lines farthest from its box;
 *    one that took part of a text gets the rest of it (the box missed a line), as far as its
 *    reading allows.
 * 3. A region that got nothing (its box is beside the text) takes the nearest whole text no other
 *    region has, of about its length and of the page's letter size, if there is one near enough.
 * 4. Its box becomes the box around its lines. A region that found nothing keeps the model's box.
 *
 * Sound effects are left as they are ([SfxLetters] finds their letters), and so are texts of only
 * dots and marks (they are not redrawn).
 */
object TextPlacer {

    fun place(
        pixels: IntArray,
        width: Int,
        height: Int,
        regions: List<TextRegion>,
        log: ((String) -> Unit)? = null,
    ): List<TextRegion> {
        val movable = regions.indices.filter { movable(regions[it]) }
        if (movable.isEmpty()) return regions
        val vertical = movable.count { verticalScript(regions[it].text) } * 2 > movable.size
        val texts = TextFinder.find(pixels, width, height, vertical)
        return place(texts, width, height, regions, movable, log)
    }

    /** [place] on texts already found (tests). */
    internal fun place(
        found: List<TextFinder.Text>,
        width: Int,
        height: Int,
        regions: List<TextRegion>,
        movable: List<Int> = regions.indices.filter { movable(regions[it]) },
        log: ((String) -> Unit)? = null,
    ): List<TextRegion> {
        // Bits of the art that passed for letters stand in tone or hatching, not on paper (light
        // letters count only on black). Light letters on the art are too uncertain to move a box
        // onto, but a box on them is on its text: it is not moved elsewhere
        val texts = found.filter { it.quality >= (if (it.light) MIN_LIGHT_QUALITY else MIN_QUALITY) }
        val lightLines = found.filter { it.light && it.letters >= MIN_LIGHT_LETTERS }.flatMap { it.lines }
        val lines = texts.flatMapIndexed { t, text -> text.lines.map { line -> Part(t, line) } }
        if (lines.isEmpty() || movable.isEmpty()) return regions
        val owner = IntArray(lines.size) { -1 }
        val expected = regions.map { expectedChars(it.text) }

        // A sound effect's letters drawn plain on paper are its own, not a bubble's text: a text
        // all inside a sound's box (a bubble's text only partly under a sound's rough box stays free)
        val sounds = regions.filter { it.type == RegionType.Sfx }.map { it.box }
        val soundTexts = texts.indices.filter { t -> sounds.any { share(texts[t].box, it) >= SOUND_INSIDE } }.toSet()

        // 1. Each line to the region whose box holds most of it (the nearer middle when two do)
        for ((u, unit) in lines.withIndex()) {
            if (unit.text in soundTexts) {
                owner[u] = SOUND
                continue
            }
            var best = -1
            var bestScore = -Float.MAX_VALUE
            for (r in movable) {
                val box = regions[r].box
                val inside = share(unit.line.box, reach(box, width, height))
                if (inside < MIN_INSIDE) continue
                val score = inside - centerDistance(unit.line.box, box) * CENTER_WEIGHT
                if (score > bestScore) {
                    best = r
                    bestScore = score
                }
            }
            owner[u] = best
        }

        // 2. Too much: give back the farthest lines. Part of a text: take the rest of it
        for (r in movable) {
            val own = lines.indices.filter { owner[it] == r }.toMutableList()
            val limit = expected[r] * MAX_EXTRA + EXTRA_CHARS
            while (own.size > 1 && own.sumOf { lines[it].line.chars.toDouble() } > limit) {
                val far = own.maxBy { centerDistance(lines[it].line.box, regions[r].box) }
                owner[far] = -1
                own -= far
            }
        }
        // A box drawn too big also takes lines of a neighbour's text: a region that has a whole
        // text of its own, about as long as it reads, gives back the lines of texts it has only part of
        for (r in movable) {
            val byText = lines.indices.filter { owner[it] == r }.groupBy { lines[it].text }
            if (byText.size < 2) continue
            val whole = byText.filter { (t, own) -> own.size == lines.count { it.text == t } }
            val wholeChars = whole.values.flatten().fold(0f) { sum, u -> sum + lines[u].line.chars }
            if (whole.isEmpty() || wholeChars < expected[r] * ENOUGH) continue
            for ((t, own) in byText) if (t !in whole) own.forEach { owner[it] = -1 }
        }
        for (r in movable) {
            val touched = lines.indices.filter { owner[it] == r }.map { lines[it].text }.distinct()
            var chars = lines.indices.filter { owner[it] == r }.sumOf { lines[it].line.chars.toDouble() }.toFloat()
            val limit = expected[r] * MAX_TAKEN + EXTRA_CHARS
            for (t in touched) {
                val ofText = lines.indices.filter { lines[it].text == t }
                // Outwards from the lines it has, one line at a time, while free and its text not too long
                var grew = true
                while (grew) {
                    grew = false
                    val mine = ofText.filter { owner[it] == r }
                    val first = ofText.indexOf(mine.first())
                    val last = ofText.indexOf(mine.last())
                    for (next in listOf(first - 1, last + 1)) {
                        val u = ofText.getOrNull(next) ?: continue
                        if (owner[u] != -1) continue
                        if (chars + lines[u].line.chars > limit) continue
                        owner[u] = r
                        chars += lines[u].line.chars
                        grew = true
                    }
                }
            }
        }

        // Lines with far fewer letters than the region reads (the dots before its words) are not its text
        for (r in movable) {
            val own = lines.indices.filter { owner[it] == r }
            if (own.fold(0f) { sum, u -> sum + lines[u].line.chars } <
                expected[r] * MIN_FOUND
            ) {
                own.forEach { owner[it] = -1 }
            }
        }

        // How far is far, on a page's height (a webtoon's long strip is as wide as a page)
        val pageSize = minOf(height.toFloat(), width * PAGE_SHAPE)
        // The page's usual letter size, from the texts placed so far: a "text" far bigger is art
        val placedSizes = lines.indices.filter { owner[it] >= 0 }.map { lines[it].line.letterHeight }
            .filter { it > 0f }.sorted()
        val pageLetters = placedSizes.getOrNull(placedSizes.size / 2)

        // 3. Regions with nothing: the nearest free whole text of about their length, best pairs first
        val homeless = movable.filter { r ->
            owner.none { it == r } && lightLines.none { share(it.box, regions[r].box) >= MIN_INSIDE }
        }.toMutableList()
        // (light letters only where the model's box is: far from it, light bits of the art are many)
        val free = texts.indices.filter { t ->
            !texts[t].light &&
                lines.indices.none { lines[it].text == t && owner[it] != -1 }
        }
            .toMutableList()
        while (homeless.isNotEmpty() && free.isNotEmpty()) {
            var best: Triple<Int, Int, Float>? = null
            for (r in homeless) {
                for (t in free) {
                    val score = farScore(regions[r], expected[r], texts[t], pageLetters, pageSize) ?: continue
                    if (best == null || score < best.third) best = Triple(r, t, score)
                }
            }
            val (r, t, _) = best ?: break
            lines.indices.filter { lines[it].text == t }.forEach { owner[it] = r }
            homeless -= r
            free -= t
        }

        // 4. Each region's box around its lines
        return regions.mapIndexed { r, region ->
            val own = lines.indices.filter { owner[it] == r }
            if (own.isEmpty()) {
                log?.invoke("kept   ${region.box} '${region.text.take(24)}'")
                return@mapIndexed region
            }
            val size = own.map { lines[it].line.letterHeight }.filter { it > 0f }.maxOrNull() ?: 0f
            val pad = (size * PAD).toInt()
            val box = Box(
                own.minOf { lines[it].line.box.left },
                own.minOf { lines[it].line.box.top },
                own.maxOf { lines[it].line.box.right },
                own.maxOf { lines[it].line.box.bottom },
            ).grown(pad, width, height)
            log?.invoke(
                "placed ${region.box} -> $box '${region.text.take(24)}' lines=${own.size} " +
                    "chars=${own.sumOf { lines[it].line.chars.toDouble() }.toInt()}/${expected[r]}",
            )
            region.copy(box = box)
        }
    }

    private class Part(val text: Int, val line: TextFinder.Line)

    /** Regions whose box is moved: text that is erased and set again (sound effects are found by their own code). */
    private fun movable(region: TextRegion) = region.type.erasesOriginal && expectedChars(region.text) > 0

    /** Japanese (and Chinese) is set in columns; Korean in lines. */
    private fun verticalScript(text: String) = text.any {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HAN
    }

    /** Characters the letters found should add up to: letters, digits, ! and ? (dots are marks). */
    private fun expectedChars(text: String) = text.count { it.isLetterOrDigit() || it == '!' || it == '?' }

    /** The model's box with a little room around it: a box drawn a bit small still holds its line. */
    private fun reach(box: Box, width: Int, height: Int) =
        box.grown((minOf(box.width, box.height) * REACH + REACH_PIXELS).toInt(), width, height)

    /** How much of [b] lies in [a]. */
    private fun share(b: Box, a: Box): Float {
        val overlap = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() *
            maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        return overlap.toFloat() / (b.width.toLong() * b.height).coerceAtLeast(1)
    }

    /** Distance between the middles of two boxes, in sizes of [box]. */
    private fun centerDistance(line: Box, box: Box): Float {
        val dx = (line.left + line.right - box.left - box.right) / 2f
        val dy = (line.top + line.bottom - box.top - box.bottom) / 2f
        return hypot(dx, dy) / sqrt(box.width.toFloat() * box.height).coerceAtLeast(1f)
    }

    /**
     * How well a whole free text suits a region whose box held no text: near, of about the
     * region's length, of the page's letter size. `null` if it doesn't at all.
     */
    private fun farScore(
        region: TextRegion,
        expected: Int,
        text: TextFinder.Text,
        pageLetters: Float?,
        pageSize: Float,
    ): Float? {
        val box = region.box
        val gapX = maxOf(text.box.left - box.right, box.left - text.box.right, 0)
        val gapY = maxOf(text.box.top - box.bottom, box.top - text.box.bottom, 0)
        val farthest = maxOf(maxOf(box.width, box.height) * FAR_REACH, pageSize * FAR_PAGE)
        if (hypot(gapX.toFloat(), gapY.toFloat()) > farthest) return null
        val chars = text.chars
        if (chars <= 0f) return null
        val ratio = chars / expected
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return null
        if (pageLetters != null && text.letterHeight > pageLetters * MAX_LETTER_SIZE) return null
        // A single letter is anything; text of a few letters needs a few letters
        if (text.letters < minOf(expected, MIN_FAR_LETTERS)) return null
        return centerDistance(text.box, box) + abs(ln(ratio)) * LENGTH_WEIGHT
    }

    /** Owner of a line that is a sound effect's: of a text at least this share inside a sound's box. */
    private const val SOUND = -2
    private const val SOUND_INSIDE = 0.8f

    /** Texts less like lettering than this are not used (see [TextFinder.Text.quality])... */
    private const val MIN_QUALITY = 0.7f

    /** ...light ones only on (nearly) plain black: on the art, light bits pass for letters too easily. */
    private const val MIN_LIGHT_QUALITY = 0.85f

    /** Light letters of a text with at least this many keep a box on them from moving elsewhere. */
    private const val MIN_LIGHT_LETTERS = 3

    /** A line goes to a region whose box (with a little room) holds at least this share of it. */
    private const val MIN_INSIDE = 0.5f
    private const val REACH = 0.15f
    private const val REACH_PIXELS = 2

    /** Between two boxes holding a line, the nearer middle wins (weight against the share inside). */
    private const val CENTER_WEIGHT = 0.25f

    /** A region holds at most this many times the characters it reads (and a few more)... */
    private const val MAX_EXTRA = 2.2f
    private const val EXTRA_CHARS = 4

    /** A region whose lines hold less than this share of the characters it reads has not found its text. */
    private const val MIN_FOUND = 0.3f

    /** A region whose other texts hold this share of its characters gives back a minority of another's lines. */
    private const val ENOUGH = 0.8f

    /** ...and takes the rest of a text while it holds at most this many times as many. */
    private const val MAX_TAKEN = 1.6f

    /** A box beside its text is moved this many of its sizes at most, or this share of the page height. */
    private const val FAR_REACH = 1.5f
    private const val FAR_PAGE = 0.06f

    /** A text taken from further away has between these times the region's characters. */
    private const val MIN_RATIO = 0.4f
    private const val MAX_RATIO = 2.5f
    private const val LENGTH_WEIGHT = 1f

    /** ...letters at most this many times the page's usual size (an eye on a white panel is bigger)... */
    private const val MAX_LETTER_SIZE = 2.5f

    /** ...and at least this many letters (or as many as the region's text has). */
    private const val MIN_FAR_LETTERS = 3

    /** A page is about this many times as high as wide. */
    private const val PAGE_SHAPE = 1.42f

    /** Room left around the letters, as a share of their height. */
    private const val PAD = 0.2f
}
