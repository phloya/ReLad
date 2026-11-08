package plus.app.translate

import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import plus.translate.ChapterVersions
import plus.translate.LetteringFonts
import plus.translate.PageTranslator
import plus.translate.TargetLanguage
import plus.translate.TranslationStore
import plus.translate.bible.BibleStore
import tachiyomi.presentation.core.util.collectAsState
import java.io.File
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
        bibles = bibles(graph),
        fonts = { LetteringFonts.load(graph.context.assets) },
        translations = translations(graph),
        // Timings only, no page text: what a slow chapter waits for (adb logcat -s ReLad)
        log = { Log.i("ReLad", it) },
        // Only chapters the reader asked to translate; the others open in the original
        versions = versions(graph),
    ).also { translator = it }

    @Volatile
    private var translator: PageTranslator? = null

    /** What pages translated with the current settings are counted under (`null`: no provider yet). */
    fun textSignature(graph: PlusGraph): String? {
        if (!graph.isEnabled(this)) return null
        return translator?.textSignature()
    }

    override fun settingsScreen(): Screen = TranslateSettingsScreen()

    /** When a chapter asked for is done, the next one is translated in the background. */
    fun translatesNextChapter(graph: PlusGraph): Boolean =
        graph.isEnabled(this) && TranslatePreferences(graph).nextChapter.get()

    @Volatile
    private var bibleStore: BibleStore? = null

    @Volatile
    private var translationStore: TranslationStore? = null

    @Volatile
    private var versionStore: ChapterVersions? = null

    /** Which chapters are translated (or being), which version each shows, how far each got. */
    fun versions(graph: PlusGraph): ChapterVersions = versionStore ?: synchronized(this) {
        versionStore ?: ChapterVersions(File(graph.context.filesDir, "relad/chapters.json"))
            .also { versionStore = it }
    }

    /** Pages' translations, kept for good in app files: the cache only holds the drawn images. */
    fun translations(graph: PlusGraph): TranslationStore = translationStore ?: synchronized(this) {
        translationStore ?: TranslationStore(File(graph.context.filesDir, "relad/translations"))
            .also { translationStore = it }
    }

    /** Each title's notes for the translator; in app files (not cache), they must survive a cache clear. */
    fun bibles(graph: PlusGraph): BibleStore = bibleStore ?: synchronized(this) {
        bibleStore ?: BibleStore(File(graph.context.filesDir, "relad/bible")).also { bibleStore = it }
    }
}

internal class TranslatePreferences(graph: PlusGraph) {
    private val prefs = graph.preferences
    val enabled = prefs.featureEnabled(TranslateFeature.id)
    val provider = prefs.string(TranslateFeature.id, "provider")
    val language = prefs.string(TranslateFeature.id, "language", defaultLanguage())
    val soundEffects = prefs.boolean(TranslateFeature.id, "sfx", true)
    // Off unless turned on: a chapter is translated when asked for, not one the reader may never open
    val nextChapter = prefs.boolean(TranslateFeature.id, "next_chapter", false)

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

        val store = remember { TranslateFeature.translations(graph) }
        var saved by remember { mutableStateOf(store.count() to store.sizeBytes()) }
        var confirmDelete by remember { mutableStateOf(false) }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                text = { Text(stringResource(R.string.plus_translate_saved_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            store.clear()
                            // The drawn pages go too, or they would still show the deleted translations
                            graph.pageCache.clear()
                            graph.engine.onSettingsChanged()
                            saved = store.count() to store.sizeBytes()
                            Toast.makeText(context, R.string.plus_translate_saved_deleted, Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(stringResource(R.string.plus_translate_saved_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

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
                        preference = prefs.nextChapter,
                        title = stringResource(R.string.plus_translate_next_chapter),
                        subtitle = stringResource(R.string.plus_translate_next_chapter_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.soundEffects,
                        title = stringResource(R.string.plus_translate_sfx),
                        subtitle = stringResource(R.string.plus_translate_sfx_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_translate_saved),
                        subtitle = stringResource(
                            R.string.plus_translate_saved_summary,
                            saved.first,
                            Formatter.formatShortFileSize(context, saved.second),
                        ),
                        onClick = { confirmDelete = true },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.plus_translate_how)),
                ),
            )
        }
    }
}
