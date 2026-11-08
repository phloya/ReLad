package plus.translate

/**
 * Text on the page the model's answer left out: what [TextFinder] finds where no region lies, once
 * the regions are put onto the page's text ([TextPlacer]). For measuring what a request leaves out
 * (the replay stand writes it for each page).
 *
 * Tried as the signal to ask a page again with reasoning after a fast answer without it; that did
 * not pay off (plus/DECISIONS.md, 08.11): most text a fast answer drops is the kind this does not
 * see (big shouts, captions on the art, credits), and on webtoon strips scanlators' notices that
 * nobody translates look like text left out.
 *
 * Only clear text counts: lines of several letters of about the page's usual size on paper (or
 * light letters on black). A page number, dots, a speck of hatching do not.
 *
 * Pure Kotlin on ARGB pixels, so it can be tested without Android.
 */
object MissedText {

    class Missed(val box: Box, val letters: Int, val lines: Int)

    /** The texts on the page that none of [regions] covers, once put onto the page's text. */
    fun find(pixels: IntArray, width: Int, height: Int, regions: List<TextRegion>): List<Missed> {
        val vertical = regions.count { TextPlacer.verticalScript(it.text) } * 2 > regions.size
        val found = TextFinder.find(pixels, width, height, vertical)
        val placed = TextPlacer.place(found, width, height, regions)
        val clear = found.filter { it.quality >= (if (it.light) MIN_LIGHT_QUALITY else MIN_QUALITY) }
        if (clear.isEmpty()) return emptyList()
        // The page's letter size: big lettering or bits of the art much larger than it are not text left out
        val sizes = clear.map { it.letterHeight }.sorted()
        val usual = sizes[sizes.size / 2]
        return clear
            .filter { it.letters >= MIN_LETTERS && it.letterHeight <= usual * MAX_SIZE }
            .filter { text ->
                val pad = (text.letterHeight * PAD).toInt()
                placed.none { share(text.box, it.box.grown(pad, width, height)) >= COVERED }
            }
            .map { Missed(it.box, it.letters, it.lines.size) }
    }

    /** How much of [a] lies in [b]. */
    private fun share(a: Box, b: Box): Float {
        val overlap = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() *
            maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        return overlap.toFloat() / (a.width.toLong() * a.height).coerceAtLeast(1)
    }

    /** As [TextPlacer] trusts found text: on paper, or light letters on black. */
    private const val MIN_QUALITY = 0.7f
    private const val MIN_LIGHT_QUALITY = 0.85f

    /** A text of fewer letters (a page number, "!?", dots) is not counted. */
    private const val MIN_LETTERS = 4

    /** Letters this many times the page's usual size are big lettering or art, not a left-out line. */
    private const val MAX_SIZE = 5f

    /** A text this much inside a region's box (grown by this share of its letters' height) is covered. */
    private const val COVERED = 0.5f
    private const val PAD = 0.5f
}
