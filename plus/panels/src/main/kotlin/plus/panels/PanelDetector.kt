package plus.panels

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * 6. A small piece beside a big panel whose facing edge is no straight border (a corner that
 *    a balloon touching the page edge cut off) is joined back to that panel.
 * 7. A panel with slanted edges also gets its four corners, so the reader can dim along them.
 */
object PanelDetector {

    /** Bump when the result changes, so pages are not served from an older version's cache. */
    const val VERSION = 6

    /** Gutter pixels are within this of the background level. */
    private const val TOLERANCE = 40

    /** A band crossing a piece is a gutter when at most this share of it belongs to the piece. */
    private const val CUT_OCCUPANCY = 0.35f

    /** Frame lines are looked for in a piece at least this share of the page, in its parts down to [LINE_PART]. */
    private const val LINE_PIECE = 0.5f
    private const val LINE_PART = 0.2f

    /** Each part of a frame-line cut is at least this share of the piece across the line. */
    private const val LINE_MIN_PART = 0.2f

    /** Darker than this is ink of a frame line. */
    private const val LINE_DARK = 90

    /** A frame line is dark along at least this share of its length. */
    private const val LINE_PRESENT = 0.85f

    /** A side of a piece at least this straight is a frame: the piece is not joined across it. */
    private const val OPEN_EDGE = 0.5f

    /** One side of a cut must run along a frame line for at least this share of its length. */
    private const val CUT_FRAME = 0.3f

    /** Samples per side of the page when measuring overlap. */
    private const val GRID = 100

    private const val LEFT = 1
    private const val TOP = 2
    private const val RIGHT = 4
    private const val BOTTOM = 8

    /** How far (px) a border may wander and still count as straight. */
    private const val EDGE_TOLERANCE = 3

    fun analyze(image: GrayImage): PageLayout = PageLayout(
        panels = findPanels(image),
        crop = BorderCrop.estimate(image),
        gaps = StripGaps.find(image),
        aspect = image.width.toFloat() / image.height,
    )

    fun findPanels(image: GrayImage): List<Box> {
        val guess = backgrounds(image)
        for (background in guess.candidates) {
            // White art at the page edge is first kept in its panel (only on white paper: on
            // black, dark art at the edge is mostly the page itself, as on title pages). If that
            // leaves panels overlapping more than without it, the art was a gutter after all
            val withLeaks = if (background.light) findWith(image, background, guess.sure, keepLeaks = true) else null
            if (withLeaks != null && withLeaks.overlapShare() <= 0.01f) return withLeaks
            val plain = findWith(image, background, guess.sure, keepLeaks = false)
            if (withLeaks != null && plain != null && withLeaks.overlapShare() <= plain.overlapShare() + 0.01f) {
                return withLeaks
            }
            return plain ?: continue
        }
        return listOf(Box.FULL)
    }

    /**
     * Panels on a page whose gutters are [background], or `null` if that finds no layout.
     * When the background is only a guess (art covers the page edges), the layout must be clear:
     * at least two panels covering much of the page.
     */
    private fun findWith(image: GrayImage, background: Background, sure: Boolean, keepLeaks: Boolean): List<Box>? {
        val w = image.width
        val h = image.height
        val isBackground = BooleanArray(w * h) { background.matches(image.pixels[it].toInt() and 0xFF) }
        // Closes breaks of up to 2 px in panel borders. Wider would also close narrow (slanted)
        // gutters, and the images are always about the same size (PanelImages), so 1 px fits all
        val radius = 1
        val core = erode(isBackground, w, h, radius)
        val outside = floodFromEdges(core, w, h)
        val gutter = growWithin(outside, isBackground, w, h, radius)
        if (keepLeaks) keepLeaksInPanels(gutter, w, h)

        val labels = IntArray(w * h)
        val pieces = label(gutter, w, h, labels)

        // Sizes are relative to the page, or to the width on a tall strip (one screen of it)
        val unit = Sizes(w, min(h, (w * 1.6f).toInt()))
        // On white paper a panel may be art without a border. Panels between black gutters are
        // light boxes; dark art on a black page (a title page) breaks into pieces, which are not
        val minFill = if (background.light) 0.03f else 0.5f
        val found = pieces
            .filter { unit.isPanelSized(it) && it.count >= minFill * it.area }
            .flatMap {
                // Frame lines are looked for only in a piece that the gutters left as most of the
                // page (panels joined by shared frames): inside a single panel, lines of the art
                // (a roof, a building, strings) look much like frames
                val lines = background.light && it.area >= LINE_PIECE * w * h && !it.coversPage(w, h)
                cut(
                    it, image, labels, w, unit, depth = 0, checkFrames = sure, frameLines = lines,
                    minLineArea = (
                        LINE_PART *
                            w *
                            h
                        ).toInt(),
                )
            }
            .filter { unit.isPanelSized(it) }
        val joins = Joins(pieces.size + 1)
        val absorbed = absorbFragments(merge(found, joins), gutter, labels, w, h, joins)
        val panels = if (background.light) joinOpenPieces(absorbed, labels, w, h, joins) else absorbed
        val roots = IntArray(pieces.size + 1) { joins.root(it) }

        val covered = panels.sumOf { it.area.toLong() }.toFloat() / (w.toLong() * h)
        if (panels.isEmpty() || covered < 0.15f) return null
        if (!sure && (panels.size < 2 || covered < 0.55f || overlap(panels) > 0.01f * covered * w * h)) return null
        return panels.map {
            Box(
                it.x0.toFloat() / w,
                it.y0.toFloat() / h,
                (it.x1 + 1f) / w,
                (it.y1 + 1f) / h,
                shape = shapeOf(it, labels, roots, w, h),
            )
        }
    }

    /**
     * The piece reaches all four page edges and fills nearly all of it: a cover or a title
     * illustration, where long straight lines are art (a horizon, lettering), not frames.
     */
    private fun Region.coversPage(w: Int, h: Int): Boolean {
        val margin = 0.02f
        return x0 <= margin * w && y0 <= margin * h && x1 >= (1 - margin) * w - 1 && y1 >= (1 - margin) * h - 1 &&
            count >= 0.8f * w * h
    }

    /**
     * How much of what the panels cover, they cover twice: sampled on a grid, each panel by its
     * outline when it has one (slanted panels overlap as boxes, not as what the reader shows).
     */
    private fun List<Box>.overlapShare(): Float {
        var covered = 0
        var twice = 0
        for (gy in 0 until GRID) {
            val y = (gy + 0.5f) / GRID
            for (gx in 0 until GRID) {
                val x = (gx + 0.5f) / GRID
                val n = count { it.covers(x, y) }
                if (n >= 1) covered++
                if (n >= 2) twice++
            }
        }
        return if (covered > 0) twice.toFloat() / covered else 0f
    }

    private fun Box.covers(x: Float, y: Float): Boolean {
        if (x < left || x > right || y < top || y > bottom) return false
        val quad = shape ?: return true
        return inside(FloatArray(8) { quad[it] }, x, y, tolerance = 0f)
    }

    /** Which labelled pieces ended up in one panel: a joined piece points to the one it joined. */
    private class Joins(size: Int) {
        private val parent = IntArray(size) { it }

        fun root(id: Int): Int {
            var i = id
            while (parent[i] != i) i = parent[i]
            return i
        }

        fun join(id: Int, into: Int) {
            val a = root(id)
            val b = root(into)
            if (a != b) parent[a] = b
        }
    }

    /**
     * The four corners of a panel whose edges are not straight across (a slanted gutter), or
     * `null` for a plain rectangle. The corners are the panel's farthest pixels towards each
     * corner of the page; they are only taken when nearly all of the panel lies inside them,
     * so a balloon sticking out of a frame does not bend the shape.
     */
    private fun shapeOf(region: Region, labels: IntArray, roots: IntArray, w: Int, h: Int): List<Float>? {
        val root = roots[region.id]
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        val corners = IntArray(8)
        var count = 0
        for (y in region.y0..region.y1) {
            for (x in region.x0..region.x1) {
                val label = labels[y * w + x]
                if (label == 0 || roots[label] != root) continue
                count++
                val sum = x + y
                val diff = x - y
                if (sum < minSum) {
                    minSum = sum
                    corners[0] = x
                    corners[1] = y
                }
                if (diff > maxDiff) {
                    maxDiff = diff
                    corners[2] = x + 1
                    corners[3] = y
                }
                if (sum > maxSum) {
                    maxSum = sum
                    corners[4] = x + 1
                    corners[5] = y + 1
                }
                if (diff < minDiff) {
                    minDiff = diff
                    corners[6] = x
                    corners[7] = y + 1
                }
            }
        }
        if (count == 0) return null
        // Corners at the box's own corners: a rectangle, nothing to add
        val box = intArrayOf(
            region.x0,
            region.y0,
            region.x1 + 1,
            region.y0,
            region.x1 + 1,
            region.y1 + 1,
            region.x0,
            region.y1 + 1,
        )
        val near = max(2f, 0.015f * max(w, h))
        if ((0 until 8).all { abs(corners[it] - box[it]) <= near }) return null
        // The panel must fill its shape: pixels left outside mean an outline that is not four
        // straight edges (a balloon, a rounded or cut corner), and then the box is safer
        val quad = FloatArray(8) { corners[it].toFloat() }
        if (!isConvex(quad)) return null
        var outside = 0
        for (y in region.y0..region.y1) {
            for (x in region.x0..region.x1) {
                val label = labels[y * w + x]
                if (label == 0 || roots[label] != root) continue
                if (!inside(quad, x + 0.5f, y + 0.5f, tolerance = 2f)) outside++
            }
        }
        if (outside > 0.005f * count) return null
        return List(8) { if (it % 2 == 0) quad[it] / w else quad[it] / h }
    }

    /** Corners go clockwise (on screen, y down) and turn the same way at each corner. */
    private fun isConvex(quad: FloatArray): Boolean {
        for (i in 0 until 4) {
            val ax = quad[2 * i]
            val ay = quad[2 * i + 1]
            val bx = quad[2 * ((i + 1) % 4)]
            val by = quad[2 * ((i + 1) % 4) + 1]
            val cx = quad[2 * ((i + 2) % 4)]
            val cy = quad[2 * ((i + 2) % 4) + 1]
            if ((bx - ax) * (cy - by) - (by - ay) * (cx - bx) <= 0f) return false
        }
        return true
    }

    /** Whether a point lies inside the convex [quad], or within [tolerance] px of it. */
    private fun inside(quad: FloatArray, px: Float, py: Float, tolerance: Float): Boolean {
        for (i in 0 until 4) {
            val ax = quad[2 * i]
            val ay = quad[2 * i + 1]
            val bx = quad[2 * ((i + 1) % 4)]
            val by = quad[2 * ((i + 1) % 4) + 1]
            val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            if (length == 0f) continue
            // Signed distance from the edge, positive outside (the edges go clockwise)
            val distance = ((by - ay) * (px - ax) - (bx - ax) * (py - ay)) / length
            if (distance > tolerance) return false
        }
        return true
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

    /**
     * A panel that runs off the page has no border there, so white art touching the page edge
     * (a face, a balloon, a sky) is flooded like a gutter and cuts the panel apart or off.
     * Gutters are narrow bands, and the page's own margin runs along several sides. A thick white
     * patch that reaches the page edge on one side only (or in one corner) is a leak: it goes
     * back to the panel.
     */
    private fun keepLeaksInPanels(gutter: BooleanArray, w: Int, h: Int) {
        // "Thick": a square of this size fits inside. Gutters on a page ~1100 px tall are 10-20 px
        val r = max(6, (0.017f * min(w, h)).roundToInt())
        val side = 2 * r + 1
        // Running sums of gutter pixels, for any square at once
        val sums = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (gutter[y * w + x]) row++
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        fun fullSquare(x: Int, y: Int): Boolean {
            if (x < r || y < r || x + r >= w || y + r >= h) return false
            val x0 = x - r
            val y0 = y - r
            val x1 = x + r + 1
            val y1 = y + r + 1
            val n =
                sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] - sums[y1 * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
            return n == side * side
        }

        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        for (start in gutter.indices) {
            if (!gutter[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var sides = 0
            var thick = false
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x == 0) sides = sides or LEFT
                if (x == w - 1) sides = sides or RIGHT
                if (y == 0) sides = sides or TOP
                if (y == h - 1) sides = sides or BOTTOM
                if (!thick && fullSquare(x, y)) thick = true
                if (x > 0 && gutter[i - 1] && !seen[i - 1]) {
                    seen[i - 1] = true
                    queue[tail++] = i - 1
                }
                if (x < w - 1 && gutter[i + 1] && !seen[i + 1]) {
                    seen[i + 1] = true
                    queue[tail++] = i + 1
                }
                if (y > 0 && gutter[i - w] && !seen[i - w]) {
                    seen[i - w] = true
                    queue[tail++] = i - w
                }
                if (y < h - 1 && gutter[i + w] && !seen[i + w]) {
                    seen[i + w] = true
                    queue[tail++] = i + w
                }
            }
            val count = Integer.bitCount(sides)
            val corner = count == 2 && sides != LEFT or RIGHT && sides != TOP or BOTTOM
            if (thick && (count == 1 || corner) && tail <= 0.15f * w * h) {
                for (k in 0 until tail) gutter[queue[k]] = false
            }
        }
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
    private fun cut(
        region: Region,
        image: GrayImage,
        labels: IntArray,
        w: Int,
        unit: Sizes,
        depth: Int,
        checkFrames: Boolean,
        frameLines: Boolean,
        minLineArea: Int,
    ): List<Region> {
        if (depth > 6) return listOf(region)
        val across = bestCut(region, labels, w, unit, horizontal = true, checkFrames)
        val along = bestCut(region, labels, w, unit, horizontal = false, checkFrames = false)
        // Panels that share a frame line, with no gutter between them (common in Berserk)
        // A part smaller than [minLineArea] is taken as one panel
        val lines = frameLines && region.area >= minLineArea
        val lineAcross = if (lines) frameLineCut(region, image, labels, w, unit, horizontal = true) else null
        val lineAlong = if (lines) frameLineCut(region, image, labels, w, unit, horizontal = false) else null
        val best = listOfNotNull(across, along, lineAcross, lineAlong).minByOrNull { it.score } ?: return listOf(region)
        return best.parts.flatMap { cut(it, image, labels, w, unit, depth + 1, checkFrames, frameLines, minLineArea) }
    }

    /**
     * A cut along a frame line that two panels share, with no gutter between them: a thin,
     * straight, solid dark line from one end of the piece to the other, not inside a dark area.
     * Lines of art rarely run that solid from frame to frame. Gutters win over such cuts.
     */
    private fun frameLineCut(
        region: Region,
        image: GrayImage,
        labels: IntArray,
        w: Int,
        unit: Sizes,
        horizontal: Boolean,
    ): Cut? {
        val lines = if (horizontal) region.height else region.width
        val length = if (horizontal) region.width else region.height
        if (lines < 20 || length < 20) return null
        val dark = BooleanArray(lines * length)
        for (l in 0 until lines) {
            for (i in 0 until length) {
                val x = if (horizontal) region.x0 + i else region.x0 + l
                val y = if (horizontal) region.y0 + l else region.y0 + i
                val index = y * w + x
                dark[l * length + i] = labels[index] == region.id && (image.pixels[index].toInt() and 0xFF) < LINE_DARK
            }
        }
        // Share of positions dark on line l, or within r lines of it (scans are a little tilted)
        fun share(l: Int, r: Int, from: Int = 0, to: Int = length): Float {
            var n = 0
            for (i in from until to) {
                for (k in max(0, l - r)..min(lines - 1, l + r)) {
                    if (dark[k * length + i]) {
                        n++
                        break
                    }
                }
            }
            return n.toFloat() / (to - from)
        }
        val wide = FloatArray(lines) { share(it, 2) }
        val unitSide = if (horizontal) unit.height else unit.width
        // Panels cut off by a frame line are not slivers
        val minPart = max(unitSide * 0.05f, lines * LINE_MIN_PART)
        val maxRun = max(6, (unitSide * 0.006f).roundToInt()) + 4
        val end = max(2, length / 20)

        var best: Cut? = null
        var l = 0
        while (l < lines) {
            if (wide[l] < LINE_PRESENT) {
                l++
                continue
            }
            val start = l
            while (l < lines && wide[l] >= LINE_PRESENT) l++
            val stop = l - 1
            // A frame line is thin: a long run is a dark area, not a line
            if (stop - start + 1 > maxRun) continue
            if (start < minPart || lines - 1 - stop < minPart) continue
            // Solid: one line (give or take one) is dark nearly all along
            val core = (start..stop).maxBy { share(it, 1) }
            val solid = share(core, 1)
            if (solid < LINE_PRESENT) continue
            // From end to end of the piece, like a frame, not a line inside the art
            if (share(core, 2, 0, end) < 0.6f || share(core, 2, length - end, length) < 0.6f) continue
            // Not inside a dark area: at least one side of the line is light
            val off = 4
            val beforeLine = if (start - off >= 0) share(start - off, 0) else 1f
            val afterLine = if (stop + off < lines) share(stop + off, 0) else 1f
            if (min(beforeLine, afterLine) >= 0.5f) continue
            val first = tighten(region, labels, w, horizontal, 0, start - 1) ?: continue
            val second = tighten(region, labels, w, horizontal, stop + 1, lines - 1) ?: continue
            val spanFirst = if (horizontal) first.width else first.height
            val spanSecond = if (horizontal) second.width else second.height
            if (spanFirst < 0.4f * length || spanSecond < 0.4f * length) continue
            val score = CUT_OCCUPANCY + 0.01f + (1 - solid) * 0.1f
            if (best == null || score < best.score) best = Cut(listOf(first, second), score)
        }
        return best
    }

    private class Cut(val parts: List<Region>, val score: Float)

    private fun bestCut(
        region: Region,
        labels: IntArray,
        w: Int,
        unit: Sizes,
        horizontal: Boolean,
        checkFrames: Boolean,
    ): Cut? {
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
            // A gutter runs along a frame line on at least one side (the other may be borderless
            // art). A band of white art across one panel (a big sound effect, smoke) has ragged
            // edges on both: the panel is not cut there. Only across (rows) and on pages with
            // clear margins: elsewhere the pieces of a page lean on such cuts more than they lose
            if (checkFrames &&
                max(
                    frameShare(first, labels, w, horizontal, end = true),
                    frameShare(second, labels, w, horizontal, end = false),
                ) < CUT_FRAME
            ) {
                continue
            }
            val score = sum / (end - start + 1) - (end - start + 1) * 0.0001f
            if (best == null || score < best.score) best = Cut(listOf(first, second), score)
        }
        return best
    }

    /**
     * How well [part]'s side that faces a cut follows one straight line (a frame line, upright or
     * slanted): the share of its columns (rows when not [horizontal]) whose outermost pixel on
     * that side lies on the best line through them. About 1 along a frame, low along ragged art.
     * [end] is the bottom (right) side, else the top (left) one.
     */
    private fun frameShare(part: Region, labels: IntArray, w: Int, horizontal: Boolean, end: Boolean): Float {
        val across = if (horizontal) part.x0..part.x1 else part.y0..part.y1
        val inward = if (horizontal) {
            (if (end) part.y1 downTo part.y0 else part.y0..part.y1)
        } else {
            (if (end) part.x1 downTo part.x0 else part.x0..part.x1)
        }
        val xs = FloatArray(across.last - across.first + 1)
        val ys = FloatArray(xs.size)
        var n = 0
        for (a in across) {
            for (b in inward) {
                val index = if (horizontal) b * w + a else a * w + b
                if (labels[index] == part.id) {
                    xs[n] = a.toFloat()
                    ys[n] = b.toFloat()
                    n++
                    break
                }
            }
        }
        if (n < 2) return 0f
        val tolerance = EDGE_TOLERANCE.toFloat()
        // Fit a line, then fit again on the points near it (balloons and art crossing a frame
        // pull the first fit off)
        var line = fitLine(xs, ys, n) { true }
        repeat(2) {
            val (k, c) = line
            line = fitLine(xs, ys, n) { abs(ys[it] - (k * xs[it] + c)) <= 4 * tolerance }
        }
        val (k, c) = line
        var on = 0
        for (i in 0 until n) if (abs(ys[i] - (k * xs[i] + c)) <= tolerance) on++
        return on.toFloat() / xs.size
    }

    /** Least-squares line y = k x + c through the points that [use] picks (all, if it picks none). */
    private fun fitLine(xs: FloatArray, ys: FloatArray, n: Int, use: (Int) -> Boolean): Pair<Float, Float> {
        var count = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            if (!use(i)) continue
            count++
            sx += xs[i]
            sy += ys[i]
            sxx += xs[i].toDouble() * xs[i]
            sxy += xs[i].toDouble() * ys[i]
        }
        if (count <
            2
        ) {
            return if (count == 0 &&
                n > 0
            ) {
                fitLine(xs, ys, n) { true }
            } else {
                0f to (if (count == 1) sy.toFloat() else 0f)
            }
        }
        val d = count * sxx - sx * sx
        if (d == 0.0) return 0f to (sy / count).toFloat()
        val k = (count * sxy - sx * sy) / d
        return k.toFloat() to ((sy - k * sx) / count).toFloat()
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

    /**
     * A balloon or a bit of white art that touches a panel's border lets the gutter in and cuts a
     * piece off the panel, most often a corner of a panel that runs off the page. Such a small
     * piece beside a much bigger panel, within its row or column, is joined back to it; smaller
     * pieces that then fall inside the joined panel go with it.
     */
    private fun absorbFragments(
        regions: List<Region>,
        gutter: BooleanArray,
        labels: IntArray,
        w: Int,
        h: Int,
        joins: Joins,
    ): List<Region> {
        val list = regions.sortedByDescending { it.area }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            search@ for (small in list.asReversed()) {
                if (small.area > 0.06f * w * h) continue
                for (big in list) {
                    if (big === small || small.area > 0.3f * big.area) continue
                    var joined = joinFragment(small, big, gutter, labels, w, h) ?: continue
                    val rest = list.filter { it !== big && it !== small }
                    val inside = rest.filter {
                        intersection(it, joined) >= 0.9f * it.area && it.area <= 0.3f * big.area
                    }
                    // Joining must not cut into a panel of its own
                    if (rest.any { it !in inside && intersection(it, joined) > 0.1f * it.area }) continue
                    joins.join(small.id, big.id)
                    for (piece in inside) {
                        joined = joined.copy(count = joined.count + piece.count)
                        joins.join(piece.id, big.id)
                    }
                    list[list.indexOf(big)] = joined
                    list.removeAll { it === small || inside.any { piece -> piece === it } }
                    changed = true
                    break@search
                }
            }
        }
        return list
    }

    /**
     * A panel without a closed frame (characters and balloons drawn on white) falls apart into
     * its pieces: the white inside it is one with the gutter. Two pieces side by side (or one
     * above the other) whose facing edges are both ragged (a balloon, hair, a figure) are joined:
     * a real gutter runs along a frame line on at least one side.
     */
    private fun joinOpenPieces(regions: List<Region>, labels: IntArray, w: Int, h: Int, joins: Joins): List<Region> {
        val list = regions.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            search@ for (i in list.indices) {
                for (j in list.indices) {
                    if (i == j) continue
                    val a = list[i]
                    val b = list[j]
                    val gapX = b.x0 - a.x1 - 1
                    val gapY = b.y0 - a.y1 - 1
                    val beside = a.x0 + a.x1 < b.x0 + b.x1 && gapX >= -0.02f * w && gapX <= 0.06f * w &&
                        min(a.y1, b.y1) - max(a.y0, b.y0) >= 0.5f * min(a.height, b.height)
                    val above = a.y0 + a.y1 < b.y0 + b.y1 && gapY >= -0.02f * h && gapY <= 0.04f * h &&
                        min(a.x1, b.x1) - max(a.x0, b.x0) >= 0.5f * min(a.width, b.width)
                    if (!beside && !above) continue
                    if (straightness(a, if (beside) RIGHT else BOTTOM, labels, w, joins) >= OPEN_EDGE) continue
                    if (straightness(b, if (beside) LEFT else TOP, labels, w, joins) >= OPEN_EDGE) continue
                    val joined = Region(
                        a.id,
                        min(a.x0, b.x0),
                        min(a.y0, b.y0),
                        max(a.x1, b.x1),
                        max(a.y1, b.y1),
                        a.count + b.count,
                    )
                    // Joining must not cover another panel
                    if (list.filterIndexed { k, _ ->
                            k != i && k != j
                        }.any { intersection(it, joined) > 0.1f * it.area }
                    ) {
                        continue
                    }
                    joins.join(b.id, a.id)
                    list[i] = joined
                    list.removeAt(j)
                    changed = true
                    break@search
                }
            }
        }
        return list
    }

    /**
     * How well [region]'s [side] follows one straight line: the share of its rows (or columns)
     * whose outermost pixel on that side lies on the best line through them. About 1 for a frame.
     */
    private fun straightness(region: Region, side: Int, labels: IntArray, w: Int, joins: Joins): Float {
        val root = joins.root(region.id)
        val alongRows = side == LEFT || side == RIGHT
        val across = if (alongRows) region.y0..region.y1 else region.x0..region.x1
        val inward = when (side) {
            LEFT -> region.x0..region.x1
            RIGHT -> region.x1 downTo region.x0
            TOP -> region.y0..region.y1
            else -> region.y1 downTo region.y0
        }
        val xs = FloatArray(across.last - across.first + 1)
        val ys = FloatArray(xs.size)
        var n = 0
        for (a in across) {
            for (b in inward) {
                val label = if (alongRows) labels[a * w + b] else labels[b * w + a]
                if (label != 0 && joins.root(label) == root) {
                    xs[n] = a.toFloat()
                    ys[n] = b.toFloat()
                    n++
                    break
                }
            }
        }
        if (n < 2) return 0f
        val tolerance = EDGE_TOLERANCE.toFloat()
        var line = fitLine(xs, ys, n) { true }
        repeat(2) {
            val (k, c) = line
            line = fitLine(xs, ys, n) { abs(ys[it] - (k * xs[it] + c)) <= 4 * tolerance }
        }
        val (k, c) = line
        var on = 0
        for (i in 0 until n) if (abs(ys[i] - (k * xs[i] + c)) <= tolerance) on++
        return on.toFloat() / xs.size
    }

    /** [big] with [small] joined to it, or `null` if [small] looks like a panel of its own. */
    private fun joinFragment(
        small: Region,
        big: Region,
        gutter: BooleanArray,
        labels: IntArray,
        w: Int,
        h: Int,
    ): Region? {
        val beside = small.y0 >= big.y0 - 0.1f * big.height && small.y1 <= big.y1 + 0.1f * big.height
        val above = small.x0 >= big.x0 - 0.1f * big.width && small.x1 <= big.x1 + 0.1f * big.width
        if (!beside && !above) return null
        val gap = if (beside) {
            distance(small.x0, small.x1, big.x0, big.x1).toFloat() / w
        } else {
            distance(small.y0, small.y1, big.y0, big.y1).toFloat() / h
        }
        if (gap >= 0.15f) return null
        if (cleanLineBetween(small, big, gutter, w, alongX = beside)) return null
        // A real panel faces its neighbour with a straight border. Right beside the big panel,
        // any other edge gives the piece away. Farther off, both facing edges must be uneven:
        // a balloon between them, not a gutter
        val own = straightness(small, big, labels, w, alongX = beside, over = small)
        val fragment = if (gap < 0.06f) {
            own < 0.9f
        } else {
            own < 0.6f && straightness(big, small, labels, w, alongX = beside, over = small) < 0.8f
        }
        if (!fragment) return null
        return Region(
            big.id,
            min(big.x0, small.x0),
            min(big.y0, small.y0),
            max(big.x1, small.x1),
            max(big.y1, small.y1),
            big.count + small.count,
        )
    }

    /**
     * Share of the lines of [over] (rows if [alongX], else columns) where [region]'s own pixels
     * reach its side that faces [other]: about 1 for a straight border, less for an edge that a
     * balloon cut.
     */
    private fun straightness(
        region: Region,
        other: Region,
        labels: IntArray,
        w: Int,
        alongX: Boolean,
        over: Region,
    ): Float {
        val from = if (alongX) max(region.y0, over.y0) else max(region.x0, over.x0)
        val to = if (alongX) min(region.y1, over.y1) else min(region.x1, over.x1)
        if (to < from) return 0f
        val towardEnd = if (alongX) {
            other.x0 + other.x1 > region.x0 + region.x1
        } else {
            other.y0 + other.y1 >
                region.y0 + region.y1
        }
        val edge = if (alongX) (if (towardEnd) region.x1 else region.x0) else (if (towardEnd) region.y1 else region.y0)
        val step = if (towardEnd) -1 else 1
        var straight = 0
        for (line in from..to) {
            for (d in 0..EDGE_TOLERANCE) {
                val pos = edge + step * d
                val index = if (alongX) line * w + pos else pos * w + line
                if (labels[index] == region.id) {
                    straight++
                    break
                }
            }
        }
        return straight.toFloat() / (to - from + 1)
    }

    /** Gap between two pixel ranges (0 when they touch or overlap). */
    private fun distance(a0: Int, a1: Int, b0: Int, b1: Int) = max(0, max(a0, b0) - min(a1, b1) - 1)

    private fun intersection(a: Region, b: Region): Int {
        val w = min(a.x1, b.x1) - max(a.x0, b.x0) + 1
        val h = min(a.y1, b.y1) - max(a.y0, b.y0) + 1
        return if (w <= 0 || h <= 0) 0 else w * h
    }

    /**
     * Whether a line of gutter (a column if [alongX], else a row) runs between [small] and [big]
     * all along [small]'s side. A balloon in the gap has an outline and text, a gutter has neither.
     */
    private fun cleanLineBetween(small: Region, big: Region, gutter: BooleanArray, w: Int, alongX: Boolean): Boolean {
        val from = if (alongX) min(small.x1, big.x1) + 1 else min(small.y1, big.y1) + 1
        val to = if (alongX) max(small.x0, big.x0) - 1 else max(small.y0, big.y0) - 1
        val spanFrom = if (alongX) small.y0 else small.x0
        val spanTo = if (alongX) small.y1 else small.x1
        val allowed = (spanTo - spanFrom + 1) / 100
        for (line in from..to) {
            var ink = 0
            for (i in spanFrom..spanTo) {
                val index = if (alongX) i * w + line else line * w + i
                if (!gutter[index]) ink++
            }
            if (ink <= allowed) return true
        }
        return false
    }

    /** Merges pieces that overlap by at least half of the smaller one (or lie inside another). */
    private fun merge(regions: List<Region>, joins: Joins): List<Region> {
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
                        joins.join(b.id, a.id)
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
