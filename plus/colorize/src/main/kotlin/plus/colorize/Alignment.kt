package plus.colorize

import kotlin.math.sqrt

/**
 * Where a point of the original page lies in the colored image: original (u, v) in 0..1 →
 * colored pixel ([offsetX] + u × [scaleX], [offsetY] + v × [scaleY]).
 */
data class Placement(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float) {
    fun x(u: Float) = offsetX + u * scaleX
    fun y(v: Float) = offsetY + v * scaleY

    /** The same placement for the colored image scaled by [factor]. */
    fun scaled(factor: Float) = Placement(scaleX * factor, scaleY * factor, offsetX * factor, offsetY * factor)
}

/**
 * Checks that the model kept the drawing in place, and fixes small shifts.
 *
 * Compares lines (edge strength) of the original and the colored image: the model changes tones
 * but must keep the lines. Tries the expected placements and small shifts around them; the best
 * match is used if it is good enough, otherwise the colored page is not used at all (a redrawn or
 * moved page would leave ghost lines).
 */
object Alignment {

    /** Below this the colored image is treated as not matching the page. Picked on test pages. */
    const val MIN_SCORE = 0.35f

    class Result(val placement: Placement, val score: Float)

    /**
     * [original] and [colored] are gray planes at a similar, small scale (a few hundred pixels).
     * [candidates] are placements in [colored]'s pixels; each is also tried shifted by up to
     * [searchFraction] of the page in both directions.
     */
    fun find(
        original: Plane,
        colored: Plane,
        candidates: List<Placement>,
        searchFraction: Float = 0.04f,
    ): Result {
        val originalEdges = original.edges()
        val coloredEdges = colored.edges()
        var best = Result(candidates.first(), -1f)
        for (candidate in candidates) {
            val stepX = candidate.scaleX * searchFraction / COARSE_STEPS
            val stepY = candidate.scaleY * searchFraction / COARSE_STEPS
            val coarse = search(originalEdges, coloredEdges, candidate, stepX, stepY, COARSE_STEPS)
            val fine =
                search(
                    originalEdges,
                    coloredEdges,
                    coarse.placement,
                    stepX / FINE_STEPS,
                    stepY / FINE_STEPS,
                    FINE_STEPS,
                )
            if (fine.score > best.score) best = fine
        }
        return best
    }

    private fun search(
        original: Plane,
        colored: Plane,
        around: Placement,
        stepX: Float,
        stepY: Float,
        steps: Int,
    ): Result {
        var best = Result(around, score(original, colored, around))
        for (dy in -steps..steps) {
            for (dx in -steps..steps) {
                if (dx == 0 && dy == 0) continue
                val placement = around.copy(
                    offsetX = around.offsetX + dx * stepX,
                    offsetY = around.offsetY + dy * stepY,
                )
                val score = score(original, colored, placement)
                if (score > best.score) best = Result(placement, score)
            }
        }
        return best
    }

    /** Normalized cross-correlation of the two edge maps under [placement] (1 = same lines). */
    fun score(originalEdges: Plane, coloredEdges: Plane, placement: Placement): Float {
        val n = originalEdges.values.size
        val sampled = FloatArray(n)
        for (y in 0 until originalEdges.height) {
            val v = (y + 0.5f) / originalEdges.height
            for (x in 0 until originalEdges.width) {
                val u = (x + 0.5f) / originalEdges.width
                sampled[y * originalEdges.width + x] = coloredEdges.sample(placement.x(u), placement.y(v))
            }
        }
        return correlation(originalEdges.values, sampled)
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val meanA = a.average().toFloat()
        val meanB = b.average().toFloat()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            ab += da * db
            aa += da * da
            bb += db * db
        }
        if (aa == 0.0 || bb == 0.0) return 0f
        return (ab / sqrt(aa * bb)).toFloat()
    }

    private const val COARSE_STEPS = 4
    private const val FINE_STEPS = 2
}
