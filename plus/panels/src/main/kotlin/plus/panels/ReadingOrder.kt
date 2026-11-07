package plus.panels

/**
 * Puts panels in reading order: tiers from top to bottom, panels of a tier from right to left
 * (manga) or left to right (comics). Works by cutting the page recursively wherever a straight
 * line crosses no panel. So a tall panel beside two short ones is read as one column: for manga
 * the tall right panel first, then the two on the left, top to bottom.
 *
 * Slanted panels overlap their neighbors' bounds a little; only the middle of each panel has to
 * stay clear of a cut. A two-page spread is read page by page: the right page first for manga.
 */
object ReadingOrder {

    /** Share of a panel's height (or width) at each end that may overlap a cut line. */
    private const val SLACK = 0.15f

    fun sort(panels: List<Box>, rightToLeft: Boolean, pageAspect: Float): List<Box> {
        // A spread: a panel-free line near the middle splits it into two pages
        if (pageAspect > 1.15f && panels.size > 1) {
            val halves = groups(panels, vertical = true).takeIf { it.size > 1 }
            if (halves != null) {
                var leftCount = 0
                var bestSplit = -1
                var bestDistance = Float.MAX_VALUE
                for (i in 0 until halves.size - 1) {
                    leftCount += halves[i].size
                    val edge = halves[i].maxOf { it.right }
                    val distance = kotlin.math.abs(edge - 0.5f)
                    if (distance < 0.15f && distance < bestDistance) {
                        bestDistance = distance
                        bestSplit = i
                    }
                }
                if (bestSplit >= 0) {
                    val left = halves.take(bestSplit + 1).flatten()
                    val right = halves.drop(bestSplit + 1).flatten()
                    val pages = if (rightToLeft) listOf(right, left) else listOf(left, right)
                    return pages.flatMap { order(it, rightToLeft) }
                }
            }
        }
        return order(panels, rightToLeft)
    }

    private fun order(panels: List<Box>, rightToLeft: Boolean): List<Box> {
        if (panels.size <= 1) return panels
        val tiers = groups(panels, vertical = false)
        if (tiers.size > 1) return tiers.flatMap { order(it, rightToLeft) }
        val columns = groups(panels, vertical = true)
        if (columns.size > 1) {
            return (if (rightToLeft) columns.asReversed() else columns).flatMap { order(it, rightToLeft) }
        }
        // No clean cut (panels interlock like a pinwheel): the highest first, ties by direction
        val rest = panels.toMutableList()
        val sorted = mutableListOf<Box>()
        while (rest.isNotEmpty()) {
            val top = rest.minOf { it.top }
            val row = rest.filter { it.top <= top + 0.5f * it.height }
            val next = if (rightToLeft) row.maxBy { it.centerX } else row.minBy { it.centerX }
            sorted += next
            rest -= next
        }
        return sorted
    }

    /**
     * Splits [panels] into groups that a line crosses between: horizontal lines (tiers, top to
     * bottom) or vertical ones if [vertical] (columns, left to right).
     */
    private fun groups(panels: List<Box>, vertical: Boolean): List<List<Box>> {
        fun start(b: Box) = if (vertical) b.left + SLACK * b.width else b.top + SLACK * b.height
        fun end(b: Box) = if (vertical) b.right - SLACK * b.width else b.bottom - SLACK * b.height

        val sorted = panels.sortedBy { start(it) }
        val result = mutableListOf<MutableList<Box>>()
        var reach = Float.NEGATIVE_INFINITY
        for (panel in sorted) {
            if (result.isEmpty() || start(panel) >= reach) {
                result += mutableListOf(panel)
            } else {
                result.last() += panel
            }
            reach = maxOf(reach, end(panel))
        }
        return result
    }
}
