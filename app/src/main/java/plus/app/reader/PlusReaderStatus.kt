package plus.app.reader

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.app.Plus
import plus.app.translate.TranslateFeature
import plus.core.ai.AiException
import plus.core.pipeline.PageProcessingEngine.PageState
import java.util.Collections
import java.util.WeakHashMap

/**
 * A small label over the reader's page that says how Re:Lad is getting on with the open chapter:
 * "Translation: 5 of 17", "Translation ready", or, in red, why it stopped (no key, no credits, the
 * model refused...). Tapping the red label explains more and offers to retry. Added to the reader
 * from our code, no Mihon layout is changed.
 */
internal object PlusReaderStatus {

    private class Label(val view: TextView) {
        var chapter: ReaderChapter? = null
        var wasDone = false
        var doneSince = 0L
        var error: Throwable? = null
        var failed = 0
    }

    private val labels: MutableMap<ReaderActivity, Label> = Collections.synchronizedMap(WeakHashMap())

    /** The chapter the reader shows now; the label follows it. */
    fun show(activity: ReaderActivity, chapter: ReaderChapter) {
        synchronized(labels) {
            labels[activity]?.let {
                it.chapter = chapter
                return
            }
        }
        activity.runOnUiThread {
            synchronized(labels) {
                if (labels[activity] != null) return@runOnUiThread
                val label = Label(createView(activity)).also { it.chapter = chapter }
                labels[activity] = label
                activity.lifecycleScope.launch { update(activity, label) }
            }
        }
    }

    private suspend fun update(activity: ReaderActivity, label: Label) {
        val graph = Plus.graph(activity)
        while (isActive()) {
            val chapter = label.chapter
            val pages = chapter?.pages
            val versions = TranslateFeature.versions(graph)
            // Only a chapter asked to be translated: the others are read in the original
            val whole = chapter != null && graph.isEnabled(TranslateFeature) && versions.translates(chapter.chapter.id)
            var done = 0
            var failed = 0
            var error: Throwable? = null
            if (chapter != null && pages != null && graph.anyEnabled()) {
                withContext(Dispatchers.Default) {
                    val known = PlusReader.knownPages(chapter)
                    for (page in pages) {
                        val k = known[page.index] ?: continue
                        val state = graph.engine.state(k.hash, k.context)
                        if (state is PageState.Failed) {
                            failed++
                            error = state.error
                        }
                    }
                    // Kept with the chapter, not counted from the pages seen since it opened: leaving the
                    // reader and coming back showed 1 of 20 for a chapter half done
                    done = versions.progress(chapter.chapter.id, TranslateFeature.textSignature(graph), pages.size).done
                }
            }
            label.error = error
            label.failed = failed
            val total = pages?.size ?: 0
            val translating = graph.features.any { it.id == TRANSLATE && graph.isEnabled(it) }
            val text: String? = when {
                !graph.anyEnabled() || total == 0 -> null
                error != null -> activity.getString(R.string.plus_status_stopped, reason(activity, error!!))
                !whole -> null
                done < total -> activity.getString(
                    if (translating) R.string.plus_status_translating else R.string.plus_status_processing,
                    done,
                    total,
                )
                else -> {
                    val now = System.currentTimeMillis()
                    if (!label.wasDone) label.doneSince = now
                    if (now - label.doneSince < DONE_SHOWN_MS && translating) {
                        activity.getString(R.string.plus_status_done)
                    } else {
                        null
                    }
                }
            }
            label.wasDone = whole && total > 0 && done == total
            render(label.view, text, error != null)
            delay(REFRESH_MS)
        }
    }

    private suspend fun isActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun createView(activity: ReaderActivity): TextView {
        val dp = activity.resources.displayMetrics.density
        val view = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt()
            visibility = View.GONE
            setOnClickListener { explain(activity) }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = (TOP_MARGIN_DP * dp).toInt() }
        activity.addContentView(view, params)
        return view
    }

    private suspend fun render(view: TextView, text: String?, error: Boolean) = withContext(Dispatchers.Main) {
        if (text == null) {
            view.visibility = View.GONE
            return@withContext
        }
        view.text = text
        view.background = GradientDrawable().apply {
            cornerRadius = 16 * view.resources.displayMetrics.density
            setColor(if (error) ERROR_COLOR else NORMAL_COLOR)
        }
        view.isClickable = error
        view.visibility = View.VISIBLE
    }

    /** Why it stopped, the provider's own words, and a Retry that clears the failures at once. */
    private fun explain(activity: ReaderActivity) {
        val label = labels[activity] ?: return
        val error = label.error ?: return
        val message = buildString {
            append(reason(activity, error).replaceFirstChar { it.uppercase() }).append('.')
            append("\n\n").append(activity.getString(R.string.plus_status_stopped_pages, label.failed))
            error.message?.let { append("\n\n").append(it) }
            append("\n\n").append(activity.getString(R.string.plus_status_retry_note))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.plus_status_stopped_title)
            .setMessage(message)
            .setPositiveButton(R.string.plus_status_retry) { _, _ -> Plus.graph(activity).engine.retryFailed() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** The reason in the reader's words: "no API key", "out of credits", "the model refused"... */
    fun reason(context: Context, error: Throwable): String {
        val ai = error as? AiException ?: return error.message ?: error.javaClass.simpleName
        return when (ai.kind) {
            AiException.Kind.NoProvider -> context.getString(R.string.plus_reason_no_provider)
            AiException.Kind.NoKey -> context.getString(R.string.plus_reason_no_key)
            AiException.Kind.Http -> when (ai.httpCode) {
                401, 403 -> context.getString(R.string.plus_reason_bad_key)
                402 -> context.getString(R.string.plus_reason_no_credits)
                429 -> context.getString(R.string.plus_reason_rate_limit)
                404 -> context.getString(R.string.plus_reason_no_model)
                else -> context.getString(R.string.plus_reason_http, ai.httpCode ?: 0)
            }
            AiException.Kind.Network -> context.getString(R.string.plus_reason_network)
            AiException.Kind.Refused -> context.getString(R.string.plus_reason_refused)
            AiException.Kind.BadResponse -> context.getString(R.string.plus_reason_bad_answer)
            AiException.Kind.Unsupported -> ai.message ?: ai.kind.name
        }
    }

    private const val TRANSLATE = "translate"
    private const val REFRESH_MS = 1_000L
    private const val DONE_SHOWN_MS = 3_000L
    private const val TOP_MARGIN_DP = 40
    private const val NORMAL_COLOR = 0xCC202020.toInt()
    private const val ERROR_COLOR = 0xE6B3261E.toInt()
}
