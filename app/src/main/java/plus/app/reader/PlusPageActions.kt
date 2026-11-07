package plus.app.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import plus.app.Plus
import plus.app.translate.TranslateFeature
import tachiyomi.presentation.core.components.ActionButton

/**
 * Re:Lad's button in the reader's page actions (long press on a page): Translate again, this page
 * or the whole chapter. The kept translation is replaced; asks first, as it uses tokens again.
 */
@Composable
fun RowScope.PlusPageActions(activity: ReaderActivity, page: ReaderPage, onDismissRequest: () -> Unit) {
    val graph = remember { Plus.graph(activity) }
    if (!graph.isEnabled(TranslateFeature)) return
    var asking by remember { mutableStateOf(false) }
    ActionButton(
        modifier = Modifier.weight(1f),
        // Short: five buttons share the row, a long word would break in the middle
        title = stringResource(R.string.plus_retranslate_button),
        icon = MaterialSymbols.Rounded.Refresh,
        onClick = { asking = true },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.plus_retranslate)) },
            text = { Text(stringResource(R.string.plus_retranslate_message)) },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            asking = false
                            onDismissRequest()
                            PlusReader.retranslatePage(activity, page)
                        },
                    ) { Text(stringResource(R.string.plus_retranslate_page)) }
                    TextButton(
                        onClick = {
                            asking = false
                            onDismissRequest()
                            PlusReader.retranslateChapter(activity, page)
                        },
                    ) { Text(stringResource(R.string.plus_retranslate_chapter)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
