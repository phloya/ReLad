package plus.app.panels

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import plus.app.Plus
import plus.app.PlusFeature
import plus.app.PlusGraph
import plus.core.pipeline.PageProcessor

/**
 * Panel-by-panel reading: the reader zooms to one panel at a time, a swipe or a tap goes to the
 * next panel, after the last one to the next page. Panels are found on the phone (plus/panels).
 * Not a page step: the page image stays as it is, only the view moves. See plus/DECISIONS.md.
 */
object PanelsFeature : PlusFeature {
    override val id = "panels"
    override val titleRes = R.string.plus_feature_panels
    override val summaryRes = R.string.plus_feature_panels_summary
    override val processesPages = false

    override fun createProcessor(graph: PlusGraph): PageProcessor? = null

    override fun settingsScreen(): Screen = PanelsSettingsScreen()
}

internal class PanelsPreferences(graph: PlusGraph) {
    private val prefs = graph.preferences
    val enabled = prefs.featureEnabled(PanelsFeature.id)

    /** Show the whole page before its first panel. */
    val wholePageFirst = prefs.boolean(PanelsFeature.id, "page_first", false)

    /** Darken what is around the panel being read. */
    val dimOthers = prefs.boolean(PanelsFeature.id, "dim", true)

    /** [AUTO], [RIGHT_TO_LEFT] or [LEFT_TO_RIGHT]. */
    val order = prefs.string(PanelsFeature.id, "order", AUTO)

    companion object {
        const val AUTO = "auto"
        const val RIGHT_TO_LEFT = "rtl"
        const val LEFT_TO_RIGHT = "ltr"
    }
}

class PanelsSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val prefs = remember { PanelsPreferences(Plus.graph(context)) }

        Scaffold(
            topBar = { AppBar(title = stringResource(R.string.plus_feature_panels), navigateUp = navigator::pop) },
        ) { contentPadding ->
            PreferenceScreen(
                contentPadding = contentPadding,
                items = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled,
                        title = stringResource(R.string.plus_feature_enabled),
                        subtitle = stringResource(R.string.plus_feature_panels_summary),
                        onValueChanged = {
                            PlusPanels.onSwitched()
                            true
                        },
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.order,
                        entries = mapOf(
                            PanelsPreferences.AUTO to stringResource(R.string.plus_panels_order_auto),
                            PanelsPreferences.RIGHT_TO_LEFT to stringResource(R.string.plus_panels_order_rtl),
                            PanelsPreferences.LEFT_TO_RIGHT to stringResource(R.string.plus_panels_order_ltr),
                        ),
                        title = stringResource(R.string.plus_panels_order),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.wholePageFirst,
                        title = stringResource(R.string.plus_panels_page_first),
                        subtitle = stringResource(R.string.plus_panels_page_first_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.dimOthers,
                        title = stringResource(R.string.plus_panels_dim),
                        subtitle = stringResource(R.string.plus_panels_dim_summary),
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.plus_panels_how)),
                ),
            )
        }
    }
}
