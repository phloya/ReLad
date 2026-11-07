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
import eu.kanade.tachiyomi.R
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Public
import plus.app.Plus

/**
 * The reader's bottom-bar button that switches between the original pages and Re:Lad's version
 * (translated, enhanced). Shown only while a feature is on. Bright: Re:Lad's version; dimmed: the original.
 */
@Composable
fun PlusReaderButton() {
    val context = LocalContext.current
    val graph = remember { Plus.graph(context) }
    if (!graph.anyEnabled()) return
    val original by PlusReader.showOriginal.collectAsState()
    IconButton(onClick = PlusReader::toggleOriginal) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Public,
            contentDescription = stringResource(
                if (original) R.string.plus_reader_show_translation else R.string.plus_reader_show_original,
            ),
            tint = if (original) LocalContentColor.current.copy(alpha = DIMMED) else LocalContentColor.current,
        )
    }
}

private const val DIMMED = 0.38f
