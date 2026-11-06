package plus.app.donate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.roundedfilled.VolunteerActivism
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.res.stringResource as androidStringResource

/**
 * Re:Lad donation links. Replaces Mihon's "Support us" screen, whose links fund Mihon.
 * The "Support us" entry in the More tab stays hidden while [links] is empty.
 */
object PlusDonations {

    data class Link(val title: String, val url: String)

    // Add the owner's donation links here, e.g. Link("Boosty", "https://boosty.to/<name>").
    val links: List<Link> = emptyList()

    val isAvailable: Boolean
        get() = links.isNotEmpty()
}

class PlusDonateScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_support_us),
                    navigateUp = navigator::pop,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                contentPadding = paddingValues,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                item {
                    Text(
                        text = androidStringResource(R.string.plus_donate_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
                items(PlusDonations.links) { link ->
                    Card(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
                        TextPreferenceWidget(
                            title = link.title,
                            icon = MaterialSymbols.RoundedFilled.VolunteerActivism,
                            widget = {
                                Icon(
                                    imageVector = MaterialSymbols.AutoMirroredRounded.OpenInNew,
                                    contentDescription = null,
                                )
                            },
                            onPreferenceClick = { uriHandler.openUri(link.url) },
                        )
                    }
                }
            }
        }
    }
}
