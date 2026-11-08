package plus.app.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import plus.app.Plus
import plus.app.PlusFeature
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.ui.res.stringResource as androidStringResource

/** The "Re:Lad" entry at the top of the More tab. */
@Composable
fun PlusMoreEntry() {
    val navigator = LocalNavigator.currentOrThrow
    TextPreferenceWidget(
        title = "Re:Lad",
        subtitle = androidStringResource(R.string.plus_relad_summary),
        icon = ImageVector.vectorResource(R.drawable.plus_ic_sparkle),
        onPreferenceClick = { navigator.push(PlusScreen()) },
    )
}

/** Re:Lad's own screen: features, AI providers, storage. */
class PlusScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { Plus.graph(context) }
        var cacheSize by remember { mutableLongStateOf(graph.pageCache.sizeBytes()) }
        val providersJson by graph.preferences.aiProvidersJson.collectAsState()
        val providerCount = remember(providersJson) { graph.preferences.aiProviders().size }

        Scaffold(
            topBar = { AppBar(title = "Re:Lad", navigateUp = navigator::pop) },
        ) { contentPadding ->
            PreferenceScreen(
                contentPadding = contentPadding,
                items = listOf(
                    Preference.PreferenceGroup(
                        title = androidStringResource(R.string.plus_section_features),
                        preferenceItems = graph.features.map { featureItem(it) },
                    ),
                    Preference.PreferenceGroup(
                        title = androidStringResource(R.string.plus_section_ai),
                        preferenceItems = listOf(
                            Preference.PreferenceItem.TextPreference(
                                title = androidStringResource(R.string.plus_ai_providers),
                                subtitle = if (providerCount == 0) {
                                    androidStringResource(R.string.plus_ai_providers_summary_none)
                                } else {
                                    androidStringResource(R.string.plus_ai_providers_summary, providerCount)
                                },
                                onClick = { navigator.push(PlusAiProvidersScreen()) },
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = androidStringResource(R.string.plus_section_storage),
                        preferenceItems = listOf(
                            Preference.PreferenceItem.TextPreference(
                                title = androidStringResource(R.string.plus_cache_clear),
                                subtitle = androidStringResource(
                                    R.string.plus_cache_summary,
                                    Formatter.formatShortFileSize(context, cacheSize),
                                ),
                                onClick = {
                                    graph.pageCache.clear()
                                    cacheSize = graph.pageCache.sizeBytes()
                                    graph.engine.onSettingsChanged()
                                    Toast.makeText(context, R.string.plus_cache_cleared, Toast.LENGTH_SHORT).show()
                                },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Composable
    private fun featureItem(feature: PlusFeature): Preference.PreferenceItem<out Any, out Any> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { Plus.graph(context) }
        val title = androidStringResource(feature.titleRes)
        val summary = androidStringResource(feature.summaryRes)
        val enabledPreference = remember(feature.id) { graph.preferences.featureEnabled(feature.id) }
        val enabled by enabledPreference.collectAsState()
        val settings = feature.settingsScreen()
        return when {
            !graph.isAvailable(feature) -> Preference.PreferenceItem.TextPreference(
                title = title,
                subtitle = androidStringResource(R.string.plus_feature_soon) + " · " + summary,
            )
            settings != null -> Preference.PreferenceItem.TextPreference(
                title = title,
                subtitle = androidStringResource(
                    if (enabled) R.string.plus_feature_on else R.string.plus_feature_off,
                ) + " · " + summary,
                onClick = { navigator.push(settings) },
            )
            else -> Preference.PreferenceItem.SwitchPreference(
                preference = enabledPreference,
                title = title,
                subtitle = summary,
            )
        }
    }
}
