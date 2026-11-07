package plus.app.translate

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import plus.app.ui.PlusAiProvidersScreen
import plus.core.pipeline.PageProcessor
import plus.translate.PageTranslator
import plus.translate.TargetLanguage
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

object TranslateFeature : PlusFeature {
    override val id = "translate"
    override val titleRes = R.string.plus_feature_translate
    override val summaryRes = R.string.plus_feature_translate_summary

    override fun createProcessor(graph: PlusGraph): PageProcessor = PageTranslator(
        settings = {
            val prefs = TranslatePreferences(graph)
            PageTranslator.Settings(
                provider = graph.aiProvider(prefs.provider.get()),
                language = TargetLanguage.of(prefs.language.get()),
                translateSoundEffects = prefs.soundEffects.get(),
            )
        },
        clientFor = graph::aiClient,
    )

    override fun settingsScreen(): Screen = TranslateSettingsScreen()
}

internal class TranslatePreferences(graph: PlusGraph) {
    private val prefs = graph.preferences
    val enabled = prefs.featureEnabled(TranslateFeature.id)
    val provider = prefs.string(TranslateFeature.id, "provider")
    val language = prefs.string(TranslateFeature.id, "language", defaultLanguage())
    val soundEffects = prefs.boolean(TranslateFeature.id, "sfx", true)

    private fun defaultLanguage(): String {
        val device = Locale.getDefault().language
        return TargetLanguage.all.firstOrNull { it.code == device }?.code ?: "en"
    }
}

class TranslateSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { Plus.graph(context) }
        val prefs = remember { TranslatePreferences(graph) }
        val providersJson by graph.preferences.aiProvidersJson.collectAsState()
        val providers = remember(providersJson) { graph.preferences.aiProviders() }
        val providerId by prefs.provider.collectAsState()

        Scaffold(
            topBar = {
                AppBar(title = stringResource(R.string.plus_feature_translate), navigateUp = navigator::pop)
            },
        ) { contentPadding ->
            PreferenceScreen(
                contentPadding = contentPadding,
                items = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled,
                        title = stringResource(R.string.plus_feature_enabled),
                        subtitle = stringResource(R.string.plus_feature_translate_summary),
                    ),
                    if (providers.isEmpty()) {
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(R.string.plus_ai_provider_choose),
                            subtitle = stringResource(R.string.plus_translate_no_providers),
                            onClick = { navigator.push(PlusAiProvidersScreen()) },
                        )
                    } else {
                        Preference.PreferenceItem.BasicListPreference(
                            value = providerId,
                            entries = providers.associate { it.id to "${it.name} · ${it.model}" },
                            title = stringResource(R.string.plus_ai_provider_choose),
                            subtitleProvider = { value, entries ->
                                entries[value] ?: stringResource(R.string.plus_ai_provider_none_selected)
                            },
                            onValueChanged = { prefs.provider.set(it) },
                        )
                    },
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.language,
                        entries = TargetLanguage.all.associate { it.code to it.nativeName },
                        title = stringResource(R.string.plus_translate_language),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.soundEffects,
                        title = stringResource(R.string.plus_translate_sfx),
                        subtitle = stringResource(R.string.plus_translate_sfx_summary),
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.plus_translate_how)),
                ),
            )
        }
    }
}
