package plus.translate

/**
 * A webtoon strip is drawn on band by band: rows around its texts, each band on its own. Its
 * pixels are then never all in memory at once (a 800 × 15 000 strip is 48 MB of them, and the text
 * search's work arrays as much again), so several strips can be drawn side by side; and rows with
 * no text at all are not searched. A page that is not a strip is one band.
 *
 * Every text lies inside one band with room around it: as much as finding its bubble, its letters
 * and the art to draw on from ever looks around a box.
 */
object PageBands {

    /** Rows [top] until [bottom] of the page, and the texts (indices into the page's) in them. */
    class Band(val top: Int, val bottom: Int, val regions: List<Int>) {
        val rows: Int get() = bottom - top
    }

    fun of(width: Int, height: Int, regions: List<TextRegion>): List<Band> {
        if (regions.isEmpty()) return emptyList()
        if (!StripParts.isStrip(width, height)) return listOf(Band(0, height, regions.indices.toList()))
        val margin = (width * MARGIN).toInt()
        val spans = regions.indices
            .map { i ->
                val box = regions[i].box
                val room = maxOf(margin, maxOf(box.width, box.height) * BOX_MARGIN)
                Triple(
                    (box.top - room).coerceIn(0, height),
                    (box.bottom + room).coerceIn(0, height),
                    i,
                )
            }
            .sortedBy { it.first }
        val bands = mutableListOf<Band>()
        var top = spans[0].first
        var bottom = spans[0].second
        var inBand = mutableListOf(spans[0].third)
        for ((from, to, i) in spans.drop(1)) {
            // Overlapping bands are one (a text's surroundings are found before anything near is
            // erased); so are bands close together, while not too long (two passes over nearly the
            // same rows cost more than one, but a long band is many pixels at once)
            val merged = maxOf(bottom, to) - top
            if (from <= bottom || (from <= bottom + margin && merged <= width * MAX_ROWS)) {
                bottom = maxOf(bottom, to)
                inBand += i
            } else {
                bands += Band(top, bottom, inBand.sorted())
                top = from
                bottom = to
                inBand = mutableListOf(i)
            }
        }
        bands += Band(top, bottom, inBand.sorted())
        return bands.filter { it.rows > 0 }
    }

    /** [region] in a band's rows from [top] (negative: back to the page's). */
    fun shifted(region: TextRegion, top: Int): TextRegion = region.copy(
        box = Box(region.box.left, region.box.top - top, region.box.right, region.box.bottom - top),
    )

    /** Rows around a text: at least this share of the page's width... */
    private const val MARGIN = 0.5f

    /** ...or this many times the box's longer side (a bubble's outline, a sound's letters, art to copy). */
    private const val BOX_MARGIN = 2

    /** Bands near each other are joined up to this many page widths of rows. */
    private const val MAX_ROWS = 4
}
