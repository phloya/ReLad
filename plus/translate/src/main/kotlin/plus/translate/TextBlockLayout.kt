package plus.translate

/**
 * The room text may take, row by row: for each page row from [top], the first and last x
 * (-1 for a row with no room). A bubble's interior, or a plain rectangle.
 */
class Room(val top: Int, private val left: IntArray, private val right: IntArray) {
    val bottom: Int get() = top + left.size
    val height: Int get() = left.size

    /** The horizontal room every row between [fromY] and [toY] has, as (left, right); `null` if none. */
    fun between(fromY: Float, toY: Float): Pair<Float, Float>? {
        val first = fromY.toInt()
        val last = kotlin.math.ceil(toY).toInt() - 1
        if (first < top || last >= bottom || last < first) return null
        var l = Int.MIN_VALUE
        var r = Int.MAX_VALUE
        for (y in first..last) {
            val i = y - top
            if (left[i] < 0) return null
            l = maxOf(l, left[i])
            r = minOf(r, right[i])
        }
        return if (r > l) l.toFloat() to r.toFloat() else null
    }

    /**
     * The room's middle line: the median of its rows' middles. A bubble's tail or a bump on one
     * side moves a row's middle, not this.
     */
    val axis: Float by lazy {
        val middles = (left.indices).filter { left[it] >= 0 }.map { (left[it] + right[it]) / 2f }.sorted()
        if (middles.isEmpty()) 0f else middles[middles.size / 2]
    }

    fun contains(x: Int, y: Int): Boolean {
        val i = y - top
        return i in left.indices && left[i] >= 0 && x >= left[i] && x <= right[i]
    }

    /** This room, but only inside [box]. */
    fun within(box: Box): Room? {
        val from = maxOf(top, box.top)
        val to = minOf(bottom, box.bottom)
        if (to <= from) return null
        val l = IntArray(to - from)
        val r = IntArray(to - from)
        for (y in from until to) {
            val i = y - top
            val a = maxOf(left[i], box.left)
            val b = minOf(right[i], box.right - 1)
            if (left[i] < 0 || b <= a) {
                l[y - from] = -1
                r[y - from] = -1
            } else {
                l[y - from] = a
                r[y - from] = b
            }
        }
        return Room(from, l, r)
    }

    companion object {
        fun of(box: Box) = Room(box.top, IntArray(box.height) { box.left }, IntArray(box.height) { box.right - 1 })

        /** From [BubbleEraser.Result.rows]: two numbers per row from [top]. */
        fun ofRows(top: Int, rows: IntArray) = Room(
            top,
            IntArray(rows.size / 2) { rows[it * 2] },
            IntArray(rows.size / 2) { rows[it * 2 + 1] },
        )
    }
}

/** The translation's lines, ready to draw. */
class TextBlock(val size: Float, val lines: List<Line>) {
    class Line(val text: String, val centerX: Float, val baseline: Float)
}

/**
 * Sets text the way a manga letterer does: a fixed letter size (the original's), lines as wide as
 * the bubble allows at their height, so the block follows the bubble's shape, and placed where
 * the original text was. Only when the text doesn't fit is it set smaller.
 */
object TextBlockLayout {

    class Style(
        val measurer: TextMeasurer,
        /** Distance between baselines, as a share of the text size. */
        val lineHeight: Float,
        /** Space kept to the room's sides: this share of the row's width... */
        val padShare: Float,
        /** ...but at least this many cap heights. */
        val minPad: Float,
    )

    /**
     * Lines of [text] in [room] at [size], or smaller down to [minSize]; around ([anchorX],
     * [anchorY]) when possible. `null` when it doesn't fit even at [minSize].
     */
    fun place(
        text: TextPieces,
        room: Room,
        anchorY: Float,
        size: Float,
        minSize: Float,
        style: Style,
    ): TextBlock? {
        if (text.size == 0) return null
        var current = size
        while (true) {
            placeAt(text, room, anchorY, current, style)?.let { return it }
            if (current <= minSize) return null
            current = maxOf(minSize, current * SHRINK)
        }
    }

    private fun placeAt(text: TextPieces, room: Room, anchorY: Float, size: Float, style: Style): TextBlock? {
        val lineHeight = size * style.lineHeight
        val cap = size * style.measurer.capHeight
        val maxLines = minOf(text.size, (room.height / lineHeight).toInt())
        if (maxLines < 1 || text.widest * size > room.widestRow()) return null
        // First near the original text's place (quarter-line steps), then anywhere in the room
        for (far in listOf(false, true)) {
            val step = if (far) lineHeight / 2 else lineHeight / 4
            for (lines in 1..maxLines) {
                val blockHeight = lines * lineHeight
                val reach = if (far) ((room.height - blockHeight) / step).toInt() + 1 else NEAR_STEPS
                for (k in 0..reach * 2) {
                    // 0, +1, -1, +2, -2, ... steps
                    val n = (k + 1) / 2 * (if (k % 2 == 1) 1 else -1)
                    val offset = n * step
                    if (far && kotlin.math.abs(offset) <= NEAR_STEPS * lineHeight / 4) continue
                    val top = anchorY - blockHeight / 2 + offset
                    tryLines(text, room, top, lines, size, lineHeight, cap, style)?.let { return it }
                }
            }
        }
        return null
    }

    private fun tryLines(
        text: TextPieces,
        room: Room,
        top: Float,
        lines: Int,
        size: Float,
        lineHeight: Float,
        cap: Float,
        style: Style,
    ): TextBlock? {
        val widths = FloatArray(lines)
        val centers = FloatArray(lines)
        val baselines = FloatArray(lines)
        for (k in 0 until lines) {
            // Letters sit around the line's middle; the room is checked where their ink is
            val baseline = top + k * lineHeight + lineHeight / 2 + cap / 2
            val (l, r) = room.between(baseline - cap * ABOVE_CAP, baseline + cap * BELOW_BASELINE) ?: return null
            val pad = maxOf(style.minPad * cap, style.padShare * (r - l))
            // Lines are centred on the bubble's axis, as a letterer sets them; on the row's own
            // middle only when the axis is too near the row's side
            val axis = room.axis
            val half = minOf(axis - l, r - axis) - pad
            val centered = half * 2 >= (r - l - 2 * pad) * MIN_AXIS_SHARE
            val width = if (centered) half * 2 else r - l - 2 * pad
            if (width < cap) return null
            widths[k] = width
            centers[k] = if (centered) axis else (l + r) / 2
            baselines[k] = baseline
        }
        val ends = LineBreaker.split(text, size, widths) ?: return null
        var start = 0
        return TextBlock(
            size,
            List(lines) { k ->
                TextBlock.Line(text.line(start, ends[k]), centers[k], baselines[k]).also { start = ends[k] }
            },
        )
    }

    private fun Room.widestRow(): Float {
        var widest = 0f
        for (y in top until bottom) between(y.toFloat(), y + 1f)?.let { widest = maxOf(widest, it.second - it.first) }
        return widest
    }

    /** A line keeps to the axis while that leaves it at least this share of the row's width. */
    private const val MIN_AXIS_SHARE = 0.75f

    /** Each try sets the text this much smaller. */
    private const val SHRINK = 0.94f

    /** How many quarter lines the block may move from the original's place before "anywhere". */
    private const val NEAR_STEPS = 3

    /** The part of the line the letters' ink takes: from a bit above the caps to below the baseline. */
    private const val ABOVE_CAP = 1.15f
    private const val BELOW_BASELINE = 0.25f
}
