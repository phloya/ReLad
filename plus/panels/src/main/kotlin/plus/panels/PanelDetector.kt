package plus.panels

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the panels of a page with plain image analysis: no model, no network, a few tens of
 * milliseconds on a phone.
 *
 * 1. The page's background (the gutter color) is read from its margins: white, or black for
 *    flashback-style pages. Pages whose edges are art (full bleed) have no gutters: one panel.
 * 2. The gutter is flooded from the page edges. Narrow passages (a broken panel border, JPEG
 *    noise) are closed first, so the flood never leaks into a panel.
 * 3. Whatever the flood does not reach is panel content; each connected piece is a candidate.
 *    Small pieces (page numbers, specks, sound effects in the gutter) are dropped.
 * 4. A piece that is really two panels joined by a speech balloon or an arm crossing the gutter
 *    is cut where a band of mostly gutter crosses it.
 * 5. Pieces that mostly overlap are merged.
 */
object PanelDetector {

    /** Bump when the result changes, so pages are not served from an older version's cache. */
    const val VERSION = 1

    /** Gutter pixels are within this of the background level. */
    private const val TOLERANCE = 40

    /** A band crossing a piece is a gutter when at most this share of it belongs to the piece. */
    private const val CUT_OCCUPANCY = 0.35f

    fun analyze(image: GrayImage): PageLayout = PageLayout(
        panels = findPanels(image),
        crop = BorderCrop.estimate(image),
        gaps = StripGaps.find(image),
        aspect = image.width.toFloat() / image.height,
    )

    fun findPanels(image: GrayImage): List<Box> {
        val guess = backgrounds(image)
        for (background in guess.candidates) {
            val panels = findWith(image, background, guess.sure) ?: continue
            return panels
        }
        return listOf(Box.FULL)
    }

    /**
     * Panels on a page whose gutters are [background], or `null` if that finds no layout.
     * When the background is only a guess (art covers the page edges), the layout must be clear:
     * at least two panels covering much of the page.
     */
    private fun findWith(image: GrayImage, background: Background, sure: Boolean): List<Box>? {
        val w = image.width
        val h = image.height
        val isBackground = BooleanArray(w * h) { background.matches(image.pixels[it].toInt() and 0xFF) }
        // Closes breaks of up to 2 px in panel borders. Wider would also close narrow (slanted)
        // gutters, and the images are always about the same size (PanelImages), so 1 px fits all
        val radius = 1
        val core = erode(isBackground, w, h, radius)
        val outside = floodFromEdges(core, w, h)
        val gutter = growWithin(outside, isBackground, w, h, radius)

        val labels = IntArray(w * h)
        val pieces = label(gutter, w, h, labels)

        // Sizes are relative to the page, or to the width on a tall strip (one screen of it)
        val unit = Sizes(w, min(h, (w * 1.6f).toInt()))
        // On white paper a panel may be art without a border. Panels between black gutters are
        // light boxes; dark art on a black page (a title page) breaks into pieces, which are not
        val minFill = if (background.light) 0.03f else 0.5f
        val found = pieces
            .filter { unit.isPanelSized(it) && it.count >= minFill * it.area }
            .flatMap { cut(it, labels, w, unit, depth = 0) }
            .filter { unit.isPanelSized(it) }
        val panels = merge(found)

        val covered = panels.sumOf { it.area.toLong() }.toFloat() / (w.toLong() * h)
        if (panels.isEmpty() || covered < 0.15f) return null
        if (!sure && (panels.size < 2 || covered < 0.55f || overlap(panels) > 0.01f * covered * w * h)) return null
        return panels.map { Box(it.x0.toFloat() / w, it.y0.toFloat() / h, (it.x1 + 1f) / w, (it.y1 + 1f) / h) }
    }

    /** A rectangle of pixels, both ends included. */
    internal data class Region(val id: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val count: Int) {
        val width get() = x1 - x0 + 1
        val height get() = y1 - y0 + 1
        val area get() = width * height
    }

    private class Sizes(val width: Int, val height: Int) {
        fun isPanelSized(r: Region) =
            r.width >= 0.05f * width && r.height >= 0.03f * height && r.area >= 0.012f * width * height
    }

    /** The gutter color: light (white paper) or dark, around [level]. */
    private class Background(val light: Boolean, val level: Int) {
        fun matches(pixel: Int) = if (light) pixel >= level - TOLERANCE else pixel <= level + TOLERANCE
    }

    private class Guess(val candidates: List<Background>, val sure: Boolean)

    /**
     * The page's gutter color, read from its margins. When art covers most of the edges (a panel
     * bleeding off the page), white and then black are tried.
     */
    private fun backgrounds(image: GrayImage): Guess {
        val w = image.width
        val h = image.height
        val band = max(2, (min(w, h) * 0.01f).roundToInt())
        val light = mutableListOf<Int>()
        val dark = mutableListOf<Int>()
        var total = 0
        fun sample(x: Int, y: Int) {
            val p = image[x, y]
            total++
            if (p >= 200) {
                light += p
            } else if (p <= 60) {
                dark += p
            }
        }
        for (y in 0 until h step 2) {
            for (x in 0 until band) {
                sample(x, y)
                sample(w - 1 - x, y)
            }
        }
        for (x in band until w - band step 2) {
            for (y in 0 until band) {
                sample(x, y)
                sample(x, h - 1 - y)
            }
        }
        fun median(samples: MutableList<Int>, default: Int) =
            if (samples.size < 20) default else samples.sorted()[samples.size / 2]
        val white = Background(true, median(light, 245))
        val black = Background(false, median(dark, 15))
        val lightShare = light.size.toFloat() / total
        val darkShare = dark.size.toFloat() / total
        return when {
            lightShare >= 0.5f -> Guess(listOf(white), sure = true)
            darkShare >= 0.5f -> Guess(listOf(black), sure = true)
            lightShare >= 0.3f && lightShare >= darkShare -> Guess(listOf(white), sure = true)
            darkShare >= 0.3f -> Guess(listOf(black), sure = true)
            else -> Guess(listOf(white, black), sure = false)
        }
    }

    /** Background pixels whose whole (2r+1)² square is background. Outside the page counts as background. */
    private fun erode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val horizontal = BooleanArray(w * h)
        val prefix = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) prefix[x + 1] = prefix[x] + if (mask[row + x]) 0 else 1
            for (x in 0 until w) {
                horizontal[row + x] = prefix[min(w, x + r + 1)] - prefix[max(0, x - r)] == 0
            }
        }
        val result = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) prefix[y + 1] = prefix[y] + if (horizontal[y * w + x]) 0 else 1
            for (y in 0 until h) {
                result[y * w + x] = prefix[min(h, y + r + 1)] - prefix[max(0, y - r)] == 0
            }
        }
        return result
    }

    /** Pixels of [mask] connected to the page edges through [mask]. */
    private fun floodFromEdges(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val reached = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var tail = 0
        fun seed(i: Int) {
            if (mask[i] && !reached[i]) {
                reached[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (i >= w) seed(i - w)
            if (i < (h - 1) * w) seed(i + w)
        }
        return reached
    }

    /** Grows [mask] by [r] steps, only over [within] pixels (undoes the erosion near the gutter). */
    private fun growWithin(mask: BooleanArray, within: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var current = mask
        repeat(r) {
            val next = current.copyOf()
            for (i in current.indices) {
                if (current[i] || !within[i]) continue
                val x = i % w
                if ((x > 0 && current[i - 1]) || (x < w - 1 && current[i + 1]) ||
                    (i >= w && current[i - w]) || (i < (h - 1) * w && current[i + w])
                ) {
                    next[i] = true
                }
            }
            current = next
        }
        return current
    }

    /** Labels the 8-connected pieces that are not [gutter] (ids from 1) and returns their bounds. */
    private fun label(gutter: BooleanArray, w: Int, h: Int, labels: IntArray): List<Region> {
        val regions = mutableListOf<Region>()
        val queue = IntArray(w * h)
        var id = 0
        for (start in gutter.indices) {
            if (gutter[start] || labels[start] != 0) continue
            id++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = id
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val n = ny * w + nx
                        if (!gutter[n] && labels[n] == 0) {
                            labels[n] = id
                            queue[tail++] = n
                        }
                    }
                }
            }
            regions += Region(id, x0, y0, x1, y1, tail)
        }
        return regions
    }

    /**
     * Cuts [region] where a band of gutter crosses it almost entirely: two panels that a speech
     * balloon or a bit of art joins across the gutter. Each side must look like a panel itself,
     * so a character's narrow neck or a thin bridge of art is not mistaken for a gutter.
     */
    private fun cut(region: Region, labels: IntArray, w: Int, unit: Sizes, depth: Int): List<Region> {
        if (depth > 6) return listOf(region)
        val across = bestCut(region, labels, w, unit, horizontal = true)
        val along = bestCut(region, labels, w, unit, horizontal = false)
        val best = listOfNotNull(across, along).minByOrNull { it.score } ?: return listOf(region)
        return best.parts.flatMap { cut(it, labels, w, unit, depth + 1) }
    }

    private class Cut(val parts: List<Region>, val score: Float)

    private fun bestCut(region: Region, labels: IntArray, w: Int, unit: Sizes, horizontal: Boolean): Cut? {
        val lines = if (horizontal) region.height else region.width
        val length = if (horizontal) region.width else region.height
        val occupancy = FloatArray(lines) { line ->
            var n = 0
            for (i in 0 until length) {
                val x = if (horizontal) region.x0 + i else region.x0 + line
                val y = if (horizontal) region.y0 + line else region.y0 + i
                if (labels[y * w + x] == region.id) n++
            }
            n.toFloat() / length
        }
        val minGap = max(2, ((if (horizontal) unit.height else unit.width) * 0.004f).roundToInt())
        val minPart = (if (horizontal) unit.height else unit.width) * 0.05f

        var best: Cut? = null
        var line = 0
        while (line < lines) {
            if (occupancy[line] > CUT_OCCUPANCY) {
                line++
                continue
            }
            val start = line
            var sum = 0f
            while (line < lines && occupancy[line] <= CUT_OCCUPANCY) sum += occupancy[line++]
            val end = line - 1
            if (start == 0 || end == lines - 1 || end - start + 1 < minGap) continue
            if (start < minPart || lines - 1 - end < minPart) continue
            val first = tighten(region, labels, w, horizontal, 0, start - 1) ?: continue
            val second = tighten(region, labels, w, horizontal, end + 1, lines - 1) ?: continue
            // Both sides span most of the piece: two panels side by side, not a head on a body
            val spanFirst = if (horizontal) first.width else first.height
            val spanSecond = if (horizontal) second.width else second.height
            if (spanFirst < 0.4f * length || spanSecond < 0.4f * length) continue
            val score = sum / (end - start + 1) - (end - start + 1) * 0.0001f
            if (best == null || score < best.score) best = Cut(listOf(first, second), score)
        }
        return best
    }

    /** Tight bounds of the piece's pixels between lines [from] and [to] of [region]. */
    private fun tighten(region: Region, labels: IntArray, w: Int, horizontal: Boolean, from: Int, to: Int): Region? {
        val ys = if (horizontal) (region.y0 + from)..(region.y0 + to) else region.y0..region.y1
        val xs = if (horizontal) region.x0..region.x1 else (region.x0 + from)..(region.x0 + to)
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        var count = 0
        for (y in ys) {
            val row = y * w
            for (x in xs) {
                if (labels[row + x] != region.id) continue
                count++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        return if (count == 0) null else Region(region.id, x0, y0, x1, y1, count)
    }

    /** Total area where panels overlap: real panels hardly do, pieces of art often. */
    private fun overlap(regions: List<Region>): Long {
        var sum = 0L
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                val w = min(a.x1, b.x1) - max(a.x0, b.x0) + 1
                val h = min(a.y1, b.y1) - max(a.y0, b.y0) + 1
                if (w > 0 && h > 0) sum += w.toLong() * h
            }
        }
        return sum
    }

    /** Merges pieces that overlap by at least half of the smaller one (or lie inside another). */
    private fun merge(regions: List<Region>): List<Region> {
        val list = regions.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val w = min(a.x1, b.x1) - max(a.x0, b.x0) + 1
                    val h = min(a.y1, b.y1) - max(a.y0, b.y0) + 1
                    if (w <= 0 || h <= 0) continue
                    if (w * h >= 0.5f * min(a.area, b.area)) {
                        list[i] = Region(
                            a.id,
                            min(a.x0, b.x0),
                            min(a.y0, b.y0),
                            max(a.x1, b.x1),
                            max(a.y1, b.y1),
                            a.count + b.count,
                        )
                        list.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
        return list
    }
}
