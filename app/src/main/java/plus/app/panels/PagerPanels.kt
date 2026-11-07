package plus.app.panels

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import androidx.core.view.children
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import okio.BufferedSource
import plus.app.Plus
import plus.panels.Box
import plus.panels.PageLayout
import plus.panels.PanelPlanner
import plus.panels.ReadingOrder
import plus.panels.Stop
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Panel-by-panel reading in the page modes. Each page holder gets the panels of its image and a
 * list of [Stop]s (views). The holder that is on screen shows one stop; moving goes to the next
 * stop, and past the last one Mihon turns the page as usual. The pages next to the current one
 * wait at their first (or last) stop, so a page turn slides from panel to panel.
 */
internal class PagerPanels(private val viewer: PagerViewer) {

    val pager: Pager get() = viewer.pager

    private val prefs = PlusPanels.preferences(viewer.activity)
    private val states: MutableMap<PagerPageHolder, HolderState> = Collections.synchronizedMap(WeakHashMap())

    private var currentPage: ReaderPage? = null
    private var enteredForward = true
    private var selectedAt = 0L
    private var animatingUntil = 0L

    @Volatile
    private var sourceLanguage: String? = null
    private var languageAsked = false

    private val touchSlop = ViewConfiguration.get(viewer.activity).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(viewer.activity).scaledMinimumFlingVelocity * 4
    private val minSwipe = 48 * viewer.activity.resources.displayMetrics.density
    private var touch: Touch? = null

    private class HolderState {
        @Volatile var generation = 0

        var layout: PageLayout? = null
        var planKey: String? = null
        var stops: List<Stop> = emptyList()

        /** The stop shown, -1 before the first one. */
        var index = -1
        var watchesLayout = false

        /** When the holder got its image and asked for its panels. */
        @Volatile var requestedAt = 0L
    }

    private class Touch(val x: Float, val y: Float) {
        var swiping = false
        val velocity: VelocityTracker = VelocityTracker.obtain()
    }

    /** Called on a background thread with the image the holder is about to show. */
    fun onImage(holder: PagerPageHolder, image: BufferedSource) {
        val state = synchronized(states) { states.getOrPut(holder) { HolderState() } }
        val generation = ++state.generation
        state.requestedAt = SystemClock.uptimeMillis()
        holder.onImageLoaded = { refresh(holder) }
        askSourceLanguage()
        // The previous layout stays until the new one is ready: a translated or enhanced version
        // of the page arriving must not throw the reader back to the first panel
        PlusPanels.detect(viewer.activity, image) { layout ->
            if (state.generation != generation) return@detect
            state.layout = layout
            if (!state.watchesLayout) {
                state.watchesLayout = true
                holder.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                    if (r - l != or - ol || b - t != ob - ot) holder.post { refresh(holder) }
                }
            }
            refresh(holder)
        }
    }

    fun onPageSelected(page: ReaderPage, forward: Boolean) {
        currentPage = page
        enteredForward = forward
        selectedAt = SystemClock.uptimeMillis()
        if (!prefs.enabled.get()) return
        holders().forEach { holder ->
            val state = states[holder] ?: return@forEach
            if (holder.page === page && state.stops.isNotEmpty()) {
                val entry = if (forward) 0 else state.stops.lastIndex
                val image = holder.image() ?: return@forEach
                if (state.index != entry || !state.isShownOn(image)) show(holder, state, image, entry, animate = true)
            } else {
                refresh(holder)
            }
        }
    }

    /**
     * Next or previous stop on the current page, or the next or previous page after the last
     * stop. `false` when panel mode has nothing to say (a chapter transition, a page still loading).
     */
    fun move(toRight: Boolean): Boolean {
        val holder = currentHolder() ?: return false
        // The image or its panels are still on their way: the first panel comes in a moment, the
        // page stays (turning it would skip all its panels)
        if (isPending(holder)) return true
        val state = states[holder] ?: return false
        val image = holder.image()?.takeIf { it.isReady } ?: return false
        if (state.index !in state.stops.indices) return false
        val forward = toRight != (viewer is R2LPagerViewer)
        val next = state.index + if (forward) 1 else -1
        if (next in state.stops.indices) {
            show(holder, state, image, next, animate = true)
        } else {
            // Turn the page here: Mihon would first pan the zoomed page ("navigate to pan")
            pager.setCurrentItem(pager.currentItem + if (toRight) 1 else -1, viewer.config.usePageTransitions)
        }
        return true
    }

    /**
     * A swipe on a page in panel mode moves between panels: the swipe is taken from the pager
     * (it would drag the page) and the image (it would pan). Pinching still zooms, and once the
     * reader has zoomed in by hand, dragging pans as usual.
     */
    fun dispatchTouch(event: MotionEvent, dispatch: (MotionEvent) -> Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch?.velocity?.recycle()
                touch = if (canSwipe()) Touch(event.x, event.y).also { it.velocity.addMovement(event) } else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touch?.swiping == true) return true
                // A second finger: a pinch, not a swipe
                end()
            }
            MotionEvent.ACTION_MOVE -> {
                val touch = touch ?: return dispatch(event)
                touch.velocity.addMovement(event)
                if (touch.swiping) return true
                val (along, across) = deltas(touch, event)
                if (abs(along) > touchSlop && abs(along) > abs(across)) {
                    touch.swiping = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    dispatch(cancel)
                    cancel.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val touch = touch
                if (touch?.swiping == true) {
                    touch.velocity.addMovement(event)
                    touch.velocity.computeCurrentVelocity(1000)
                    val (along, _) = deltas(touch, event)
                    val speed = if (isHorizontal()) touch.velocity.xVelocity else touch.velocity.yVelocity
                    end()
                    val towardsStart = when {
                        abs(along) >= minSwipe -> along > 0
                        abs(speed) >= minFlingVelocity -> speed > 0
                        else -> return true
                    }
                    // Dragging content towards the start of the screen reveals what is after it
                    val next = if (viewer is R2LPagerViewer) towardsStart else !towardsStart
                    if (next) viewer.moveToNext() else viewer.moveToPrevious()
                    return true
                }
                end()
            }
            MotionEvent.ACTION_CANCEL -> {
                val swiping = touch?.swiping == true
                end()
                if (swiping) return true
            }
            else -> if (touch?.swiping == true) return true
        }
        return dispatch(event)
    }

    /** The mode was switched on or off: reload the pages with the right settings. */
    fun onSwitched() {
        enteredForward = true
        synchronized(states) {
            states.keys.forEach { holder ->
                holder.children.filterIsInstance<PanelFocusView>().toList().forEach(holder::removeView)
            }
            states.clear()
        }
        viewer.config.imagePropertyChangedListener?.invoke()
    }

    private fun end() {
        touch?.velocity?.recycle()
        touch = null
    }

    private fun deltas(touch: Touch, event: MotionEvent): Pair<Float, Float> {
        val dx = event.x - touch.x
        val dy = event.y - touch.y
        return if (isHorizontal()) dx to dy else dy to dx
    }

    private fun isHorizontal() = viewer !is VerticalPagerViewer

    private fun canSwipe(): Boolean {
        if (!prefs.enabled.get()) return false
        val holder = currentHolder() ?: return false
        if (isPending(holder)) return true
        val state = states[holder] ?: return false
        val image = holder.image()?.takeIf { it.isReady } ?: return false
        val stop = state.stops.getOrNull(state.index) ?: return false
        // Zoomed in by hand (pinch, double tap): let the reader pan. Zoomed out, a swipe still
        // goes on to the next panel
        val animating = SystemClock.uptimeMillis() < animatingUntil
        return animating || image.scale <= stop.scale * 1.2f
    }

    /**
     * The page on screen has no first stop yet: its image is loading, or its panels are being
     * found. For a short while moves wait for it instead of turning the page. A page that fails
     * to load, or an animated one (it never asks for panels), does not hold them.
     */
    private fun isPending(holder: PagerPageHolder): Boolean {
        val state = states[holder]
        if (SystemClock.uptimeMillis() - maxOf(state?.requestedAt ?: 0L, selectedAt) >= PENDING_MS) return false
        if (state == null || state.generation == 0) {
            return !holder.hasPageView() && holder.page.status !is Page.State.Error
        }
        return state.stops.isEmpty() || state.index < 0 || holder.image()?.isReady != true
    }

    /** The holder shows an image, still or animated. */
    private fun PagerPageHolder.hasPageView() = children.any {
        (it is SubsamplingScaleImageView || it is ImageView) && it.visibility == View.VISIBLE
    }

    private fun refresh(holder: PagerPageHolder) {
        if (!prefs.enabled.get()) return
        val state = states[holder] ?: return
        val layout = state.layout ?: return
        val image = holder.image() ?: return
        if (!image.isReady || image.width == 0 || image.height == 0) return
        plan(state, layout, image)
        if (state.stops.isEmpty()) return

        val relation = relation(holder.page) ?: return
        val index = when {
            state.index in state.stops.indices -> state.index
            relation > 0 -> 0
            relation < 0 -> state.stops.lastIndex
            enteredForward -> 0
            else -> state.stops.lastIndex
        }
        // A page just opened zooms from the whole page to its first panel
        val animate = relation == 0 && state.index == -1
        show(holder, state, image, index, animate)
    }

    private fun plan(state: HolderState, layout: PageLayout, image: SubsamplingScaleImageView) {
        val crop = if (viewer.config.imageCropBorders) layout.crop else Box.FULL
        val rightToLeft = rightToLeft()
        val wholePageFirst = prefs.wholePageFirst.get()
        val key = listOf(
            image.width,
            image.height,
            image.sWidth,
            image.sHeight,
            crop,
            rightToLeft,
            wholePageFirst,
            System.identityHashCode(layout),
        ).joinToString()
        if (key == state.planKey) return
        state.planKey = key

        val panels = layout.panels.map { it.within(crop) }.filter { it.width > 0.01f && it.height > 0.01f }
        val ordered = ReadingOrder.sort(panels, rightToLeft, image.sWidth.toFloat() / image.sHeight)
        state.stops = PanelPlanner.plan(
            panels = ordered,
            imageWidth = image.sWidth.toFloat(),
            imageHeight = image.sHeight.toFloat(),
            view = PanelPlanner.Viewport(
                width = image.width.toFloat(),
                height = image.height.toFloat(),
                minScale = image.minScale,
                maxScale = image.maxScale,
            ),
            wholePageFirst = wholePageFirst,
        )
        if (state.index > state.stops.lastIndex) state.index = state.stops.lastIndex
    }

    private fun show(
        holder: PagerPageHolder,
        state: HolderState,
        image: SubsamplingScaleImageView,
        index: Int,
        animate: Boolean,
    ) {
        state.index = index
        val stop = state.stops[index]
        val center = PointF(stop.centerX, stop.centerY)
        val smooth = animate && viewer.config.usePageTransitions
        val duration = if (smooth) {
            (ANIMATION_MS * viewer.activity.animatorDurationScale).toLong().coerceAtLeast(
                1,
            )
        } else {
            0L
        }
        if (smooth) {
            image.animateScaleAndCenter(stop.scale, center)
                ?.withDuration(duration)
                ?.withEasing(SubsamplingScaleImageView.EASE_IN_OUT_QUAD)
                ?.withInterruptible(false)
                ?.start()
            animatingUntil = SystemClock.uptimeMillis() + duration
        } else {
            image.setScaleAndCenter(stop.scale, center)
        }
        // Found among the holder's children, not kept in the state: the state map holds holders
        // weakly, and a value pointing back into the holder would keep it alive
        val overlay = holder.children.filterIsInstance<PanelFocusView>().firstOrNull()
            ?: PanelFocusView(holder.context, image).also { holder.addView(it, MATCH_PARENT, MATCH_PARENT) }
        overlay.show(
            stop.focus?.let {
                RectF(it.left, it.top, it.right, it.bottom)
            },
            prefs.dimOthers.get(),
            stop.scale,
            duration,
        )
    }

    private fun HolderState.isShownOn(image: SubsamplingScaleImageView): Boolean {
        val stop = stops.getOrNull(index) ?: return false
        return abs(image.scale / stop.scale - 1f) < 0.02f
    }

    private fun rightToLeft(): Boolean = when (prefs.order.get()) {
        PanelsPreferences.RIGHT_TO_LEFT -> true
        PanelsPreferences.LEFT_TO_RIGHT -> false
        else -> when (viewer) {
            is R2LPagerViewer -> true
            is L2RPagerViewer -> false
            // Vertical pages: manga from Japanese sources is read right to left
            else -> sourceLanguage == "ja"
        }
    }

    private fun askSourceLanguage() {
        if (languageAsked) return
        languageAsked = true
        val sourceId = viewer.activity.viewModel.manga?.source ?: return
        Plus.graph(viewer.activity).scope.launch {
            sourceLanguage = viewer.activity.appGraph.sourceManager.get(sourceId)?.lang
        }
    }

    /** 0 for the current page, 1 for a page after it, -1 before it, `null` if unknown. */
    private fun relation(page: ReaderPage): Int? {
        val current = currentPage ?: return null
        if (page === current) return 0
        if (page.chapter != current.chapter) return null
        val a = page.index * 2 + if (page is InsertPage) 1 else 0
        val b = current.index * 2 + if (current is InsertPage) 1 else 0
        return a.compareTo(b)
    }

    private fun holders() = pager.children.filterIsInstance<PagerPageHolder>().toList()

    private fun currentHolder(): PagerPageHolder? {
        val page = currentPage ?: return null
        return holders().firstOrNull { it.page === page }
    }

    private fun PagerPageHolder.image(): SubsamplingScaleImageView? =
        children.firstOrNull {
            it is SubsamplingScaleImageView && it.visibility == View.VISIBLE
        } as? SubsamplingScaleImageView

    private fun Box.within(crop: Box): Box {
        if (crop == Box.FULL) return this
        fun x(v: Float) = ((v - crop.left) / crop.width).coerceIn(0f, 1f)
        fun y(v: Float) = ((v - crop.top) / crop.height).coerceIn(0f, 1f)
        return Box(x(left), y(top), x(right), y(bottom))
    }

    companion object {
        private const val ANIMATION_MS = 320L

        /** How long moves wait for a page's panels before they turn the page after all. */
        private const val PENDING_MS = 3000L
    }
}
