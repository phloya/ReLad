package plus.app.panels

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import eu.kanade.tachiyomi.R
import tachiyomi.presentation.core.util.collectAsState

/** The panel-by-panel switch in the reader's bottom bar (see plus/HOOKS.md). Lit while on. */
@Composable
fun PanelsReaderButton() {
    val context = LocalContext.current
    val preference = remember { PlusPanels.preferences(context).enabled }
    val enabled by preference.collectAsState()
    IconButton(onClick = { PlusPanels.toggle(context) }) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.plus_ic_panels),
            contentDescription = stringResource(R.string.plus_panels_reader_button),
            tint = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
