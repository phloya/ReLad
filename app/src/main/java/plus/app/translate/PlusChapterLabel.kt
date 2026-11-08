package plus.app.translate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.R
import plus.app.Plus

/**
 * The translation's mark in the manga screen's chapter list, put before the scanlator: "Translation"
 * for a chapter translated whole, "Translation 12/20" while it is on its way. Nothing for a
 * chapter read in the original.
 */
object PlusChapterLabel {

    @Composable
    fun withScanlator(chapterId: Long, scanlator: String?): String? {
        val context = LocalContext.current
        val graph = remember { Plus.graph(context) }
        if (!graph.isEnabled(TranslateFeature)) return scanlator
        val versions = remember { TranslateFeature.versions(graph) }
        val changes by versions.changes.collectAsState()
        val label = remember(changes, chapterId) {
            if (!versions.translates(chapterId)) {
                null
            } else {
                val progress = versions.progress(chapterId, TranslateFeature.textSignature(graph))
                when {
                    progress.complete -> context.getString(R.string.plus_chapter_translated)
                    progress.total > 0 -> context.getString(
                        R.string.plus_chapter_translating,
                        progress.done,
                        progress.total,
                    )
                    // Asked for, the chapter not loaded yet
                    else -> context.getString(R.string.plus_chapter_translation_started)
                }
            }
        }
        return listOfNotNull(label, scanlator).joinToString(" • ").ifEmpty { null }
    }
}
