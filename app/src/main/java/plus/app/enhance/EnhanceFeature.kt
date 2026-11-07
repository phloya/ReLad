package plus.app.enhance

import android.text.format.Formatter
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import plus.app.Plus
import plus.app.PlusFeature
import plus.app.PlusGraph
import plus.core.pipeline.PageProcessor
import plus.enhance.EnhanceModel
import plus.enhance.EnhanceModels
import plus.enhance.ModelStore
import plus.enhance.NativeUpscaler
import plus.enhance.PageEnhancer
import tachiyomi.presentation.core.util.collectAsState
import java.io.File

object EnhanceFeature : PlusFeature {
    override val id = "enhance"
    override val titleRes = R.string.plus_feature_enhance
    override val summaryRes = R.string.plus_feature_enhance_summary

    @Volatile
    private var enhancer: PageEnhancer? = null

    internal fun store(graph: PlusGraph) = ModelStore(File(graph.context.filesDir, "relad_models"), graph.httpClient)

    override fun createProcessor(graph: PlusGraph): PageProcessor = PageEnhancer(
        settings = {
            val prefs = EnhancePreferences(graph)
            PageEnhancer.Settings(
                model = prefs.model(),
                useGpu = prefs.useGpu.get(),
                maxInputSide = prefs.maxInputSide.get(),
            )
        },
        store = store(graph),
    ).also { enhancer = it }

    internal val lastRun get() = enhancer?.lastRun

    override fun settingsScreen(): Screen = EnhanceSettingsScreen()
}

internal class EnhancePreferences(graph: PlusGraph) {
    private val prefs = graph.preferences
    val enabled = prefs.featureEnabled(EnhanceFeature.id)
    val denoise = prefs.int(EnhanceFeature.id, "denoise", 1)
    val useGpu = prefs.boolean(EnhanceFeature.id, "gpu", true)
    val maxInputSide = prefs.int(EnhanceFeature.id, "max_side", 2200)
    val allowNonCommercial = prefs.boolean(EnhanceFeature.id, "non_commercial", false)

    fun model(): EnhanceModel {
        val available = EnhanceModels.available(allowNonCommercial.get())
        return EnhanceModels.cunetByNoise.getOrNull(denoise.get())?.takeIf { it in available }
            ?: available.first()
    }
}

class EnhanceSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { Plus.graph(context) }
        val prefs = remember { EnhancePreferences(graph) }
        val store = remember { EnhanceFeature.store(graph) }
        val denoise by prefs.denoise.collectAsState()
        val model = remember(denoise) { prefs.model() }
        var downloadState by remember(model) {
            mutableStateOf<String?>(
                if (store.isDownloaded(model)) context.getString(R.string.plus_enhance_model_ready) else null,
            )
        }
        val gpuName = remember { runCatching { NativeUpscaler.gpuName }.getOrNull() }
        val lastRun = EnhanceFeature.lastRun

        Scaffold(
            topBar = { AppBar(title = stringResource(R.string.plus_feature_enhance), navigateUp = navigator::pop) },
        ) { contentPadding ->
            PreferenceScreen(
                contentPadding = contentPadding,
                items = listOfNotNull(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled,
                        title = stringResource(R.string.plus_feature_enabled),
                        subtitle = stringResource(R.string.plus_feature_enhance_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.denoise,
                        entries = mapOf(
                            0 to stringResource(R.string.plus_enhance_denoise_0),
                            1 to stringResource(R.string.plus_enhance_denoise_1),
                            2 to stringResource(R.string.plus_enhance_denoise_2),
                            3 to stringResource(R.string.plus_enhance_denoise_3),
                        ),
                        title = stringResource(R.string.plus_enhance_denoise),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.maxInputSide,
                        entries = listOf(1600, 2200, 3000).associateWith {
                            stringResource(R.string.plus_enhance_max_side_value, it)
                        },
                        title = stringResource(R.string.plus_enhance_max_side),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.useGpu,
                        title = stringResource(R.string.plus_enhance_gpu),
                        subtitle = if (gpuName != null) {
                            stringResource(R.string.plus_enhance_gpu_found, gpuName)
                        } else {
                            stringResource(R.string.plus_enhance_gpu_missing)
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_enhance_model) + ": " + model.name,
                        subtitle = stringResource(
                            R.string.plus_enhance_model_info,
                            model.id,
                            model.license.name,
                            Formatter.formatShortFileSize(context, model.downloadBytes),
                        ) + (downloadState?.let { "\n$it" } ?: ""),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_enhance_download),
                        onClick = {
                            downloadState = context.getString(R.string.plus_enhance_downloading)
                            scope.launch {
                                downloadState = try {
                                    store.get(model)
                                    context.getString(R.string.plus_enhance_model_ready)
                                } catch (e: Exception) {
                                    context.getString(R.string.plus_enhance_download_failed, e.message)
                                }
                            }
                        },
                    ).takeIf { !store.isDownloaded(model) },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.allowNonCommercial,
                        title = stringResource(R.string.plus_enhance_non_commercial),
                        subtitle = stringResource(R.string.plus_enhance_non_commercial_summary),
                    ).takeIf { EnhanceModels.all.any { !it.license.commercialUse } },
                    lastRun?.let {
                        Preference.PreferenceItem.InfoPreference(
                            stringResource(
                                R.string.plus_enhance_last_run,
                                it.width,
                                it.height,
                                it.millis / 1000f,
                                stringResource(
                                    if (it.gpu) R.string.plus_enhance_on_gpu else R.string.plus_enhance_on_cpu,
                                ),
                            ),
                        )
                    },
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.plus_enhance_how)),
                ),
            )
        }
    }
}
