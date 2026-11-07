package plus.app.panels

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Darkens the page around the panel being read, like a spotlight, so the neighbors that fit on
 * the screen do not distract. Follows the image while it zooms and pans; never takes touches.
 */
@SuppressLint("ViewConstructor")
internal class PanelFocusView(context: Context, private val image: SubsamplingScaleImageView) : View(context) {

    private val paint = Paint()
    private var focus: RectF? = null
    private var strength = 0f
    private var animator: ValueAnimator? = null

    private val drawn = RectF()
    private val mapped = RectF()
    private val topLeft = PointF()
    private val bottomRight = PointF()

    // The image animates by redrawing itself; redraw too whenever the panel moved on screen
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (strength > 0f && map() && mapped != drawn) invalidate()
        true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Dims around [rect] (image pixels), or stops dimming when it is `null` or [dim] is off. */
    fun show(rect: RectF?, dim: Boolean, animate: Boolean) {
        if (rect != null) focus = RectF(rect)
        val target = if (dim && rect != null) 1f else 0f
        animator?.cancel()
        if (!animate || strength == target) {
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
        paint.color = Color.argb((MAX_ALPHA * strength).toInt(), 0, 0, 0)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, mapped.top, paint)
        canvas.drawRect(0f, mapped.bottom, w, h, paint)
        canvas.drawRect(0f, mapped.top, mapped.left, mapped.bottom, paint)
        canvas.drawRect(mapped.right, mapped.top, w, mapped.bottom, paint)
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
    }
}
