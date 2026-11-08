package plus.app.reader

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Public
import plus.app.Plus
import plus.app.translate.TranslateFeature
import plus.app.translate.TranslatePreferences
import plus.translate.TargetLanguage

/** The open chapter's versions, as the reader's buttons show them. */
private class ChapterVersionState(val activity: ReaderActivity, val translates: Boolean, val showsTranslation: Boolean)

/** `null` outside the reader, with translation off or while no chapter is open. */
@Composable
private fun chapterVersionState(): ChapterVersionState? {
    val activity = LocalContext.current.readerActivity() ?: return null
    val graph = remember { Plus.graph(activity) }
    if (!graph.isEnabled(TranslateFeature)) return null
    val state by activity.viewModel.state.collectAsState()
    val chapterId = state.currentChapter?.chapter?.id ?: return null
    val versions = remember { TranslateFeature.versions(graph) }
    val changes by versions.changes.collectAsState()
    return remember(changes, chapterId) {
        ChapterVersionState(activity, versions.translates(chapterId), versions.showsTranslation(chapterId))
    }
}

/**
 * The reader's bottom-bar button for the open chapter: Translate it, then switch between its
 * original and its translation. Shown only while translation is on. Bright: the translation is
 * shown; dimmed: the original.
 */
@Composable
fun PlusReaderButton() {
    val chapter = chapterVersionState() ?: return
    IconButton(
        onClick = {
            when {
                !chapter.translates -> PlusReader.translateChapter(chapter.activity)
                else -> PlusReader.showOriginal(chapter.activity, original = chapter.showsTranslation)
            }
        },
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Public,
            contentDescription = stringResource(
                when {
                    !chapter.translates -> R.string.plus_reader_translate_chapter
                    chapter.showsTranslation -> R.string.plus_reader_show_original
                    else -> R.string.plus_reader_show_translation
                },
            ),
            tint = if (chapter.showsTranslation) {
                LocalContentColor.current
            } else {
                LocalContentColor.current.copy(alpha = DIMMED)
            },
        )
    }
}

/**
 * Re:Lad's items in the reader's ⋯ menu: "Translate the chapter" for a chapter in the original
 * only, then its two versions to choose from, the one shown ticked.
 */
@Composable
fun plusReaderMenu(): List<AppBar.AppBarAction> {
    val chapter = chapterVersionState() ?: return emptyList()
    if (!chapter.translates) {
        return listOf(
            AppBar.OverflowAction(
                title = stringResource(R.string.plus_reader_translate_chapter),
                onClick = { PlusReader.translateChapter(chapter.activity) },
            ),
        )
    }
    val language = remember {
        TargetLanguage.of(TranslatePreferences(Plus.graph(chapter.activity)).language.get()).nativeName
    }
    return listOf(
        AppBar.OverflowAction(
            title = ticked(!chapter.showsTranslation, stringResource(R.string.plus_reader_version_original)),
            onClick = { PlusReader.showOriginal(chapter.activity, original = true) },
        ),
        AppBar.OverflowAction(
            title = ticked(
                chapter.showsTranslation,
                stringResource(R.string.plus_reader_version_translation, language),
            ),
            onClick = { PlusReader.showOriginal(chapter.activity, original = false) },
        ),
    )
}

private fun ticked(shown: Boolean, title: String) = if (shown) "✓ $title" else title

private const val DIMMED = 0.38f
