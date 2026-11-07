package plus.app.panels

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.abs

/**
 * Darkens the page around the panel being read, like a spotlight, so the neighbors that fit on
 * the screen do not distract. Follows the image while it zooms and pans, fades away when the
 * reader zooms away from the panel by hand, and never takes touches.
 */
@SuppressLint("ViewConstructor")
internal class PanelFocusView(context: Context, private val image: SubsamplingScaleImageView) : View(context) {

    private val paint = Paint()
    private var focus: RectF? = null
    private var strength = 0f
    private var animator: ValueAnimator? = null

    /** The scale the image shows the panel at, and when it gets there. */
    private var panelScale = 0f
    private var settlesAt = 0L

    private val drawn = RectF()
    private var drawnAlpha = 0
    private val mapped = RectF()
    private val topLeft = PointF()
    private val bottomRight = PointF()

    // The image animates by redrawing itself; redraw too whenever the panel moved on screen
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (strength > 0f && map() && (mapped != drawn || alpha() != drawnAlpha)) invalidate()
        true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Dims around [rect] (image pixels), or stops dimming when it is `null` or [dim] is off.
     * The image shows the panel at [scale] after [animationMs].
     */
    fun show(rect: RectF?, dim: Boolean, scale: Float, animationMs: Long) {
        // Starts from what is on screen now, which is less when the reader had zoomed away
        strength *= zoomFactor()
        if (rect != null) focus = RectF(rect)
        panelScale = scale
        settlesAt = SystemClock.uptimeMillis() + animationMs
        val target = if (dim && rect != null) 1f else 0f
        animator?.cancel()
        if (animationMs == 0L || strength == target) {
            strength = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(strength, target).apply {
            duration = FADE_MS
            addUpdateListener {
                strength = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (strength <= 0f || !map()) return
        drawn.set(mapped)
        drawnAlpha = alpha()
        if (drawnAlpha == 0) return
        paint.color = Color.argb(drawnAlpha, 0, 0, 0)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, mapped.top, paint)
        canvas.drawRect(0f, mapped.bottom, w, h, paint)
        canvas.drawRect(0f, mapped.top, mapped.left, mapped.bottom, paint)
        canvas.drawRect(mapped.right, mapped.top, w, mapped.bottom, paint)
    }

    private fun alpha() = (MAX_ALPHA * strength * zoomFactor()).toInt()

    /**
     * 1 while the image shows the panel at its scale (or is on its way there), falling to 0 as
     * the reader zooms in or out by hand: then the whole page is theirs to look at.
     */
    private fun zoomFactor(): Float {
        if (panelScale <= 0f || SystemClock.uptimeMillis() < settlesAt || !image.isReady) return 1f
        val off = abs(image.scale / panelScale - 1f)
        return ((FADE_END - off) / (FADE_END - FADE_START)).coerceIn(0f, 1f)
    }

    /** Where the focus rectangle is on screen right now. */
    private fun map(): Boolean {
        val rect = focus ?: return false
        if (!image.isReady) return false
        image.sourceToViewCoord(rect.left, rect.top, topLeft) ?: return false
        image.sourceToViewCoord(rect.right, rect.bottom, bottomRight) ?: return false
        mapped.set(
            topLeft.x + image.left,
            topLeft.y + image.top,
            bottomRight.x + image.left,
            bottomRight.y + image.top,
        )
        return true
    }

    companion object {
        private const val MAX_ALPHA = 200
        private const val FADE_MS = 250L

        // Zoomed this far from the panel's scale (a share of it), the dimming starts to fade and is gone
        private const val FADE_START = 0.05f
        private const val FADE_END = 0.2f
    }
}
