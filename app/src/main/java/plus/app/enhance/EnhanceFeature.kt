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
import kotlinx.coroutines.Dispatchers
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
import plus.enhance.ScanQuality
import plus.enhance.Strength
import plus.enhance.cloud.ReplicateModel
import plus.enhance.cloud.ReplicateModels
import plus.enhance.cloud.ReplicateUpscaler
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
                strength = prefs.fixedStrength(),
                whitenPaper = prefs.whitenPaper.get(),
                useGpu = prefs.useGpu.get(),
                maxInputSide = prefs.maxInputSide.get(),
                cloud = prefs.cloud(graph),
            )
        },
        store = store(graph).also { store ->
            // Models an older version used (waifu2x until 0.2) only take space
            graph.scope.launch(Dispatchers.IO) { store.removeOthers(EnhanceModels.all) }
        },
    ).also { enhancer = it }

    internal val lastRun get() = enhancer?.lastRun

    override fun settingsScreen(): Screen = EnhanceSettingsScreen()
}

internal class EnhancePreferences(graph: PlusGraph) {
    private val prefs = graph.preferences
    val enabled = prefs.featureEnabled(EnhanceFeature.id)

    /** [AUTO] or a [Strength] name. */
    val strength = prefs.string(EnhanceFeature.id, "strength", AUTO)
    val whitenPaper = prefs.boolean(EnhanceFeature.id, "whiten_paper", true)
    val useGpu = prefs.boolean(EnhanceFeature.id, "gpu", true)
    val maxInputSide = prefs.int(EnhanceFeature.id, "max_side", 2200)
    val allowNonCommercial = prefs.boolean(EnhanceFeature.id, "non_commercial", false)

    /** [DEVICE] or [REPLICATE]. */
    val backend = prefs.string(EnhanceFeature.id, "backend", DEVICE)

    /** A [ReplicateModels] preset id or [ReplicateModel.CUSTOM]. */
    val replicateModel = prefs.string(EnhanceFeature.id, "replicate_model", ReplicateModels.realEsrgan.id)
    val replicateCustomModel = prefs.string(EnhanceFeature.id, "replicate_custom_model", "")
    val replicateCustomField = prefs.string(EnhanceFeature.id, "replicate_custom_field", "image")
    val replicateCustomInput = prefs.string(EnhanceFeature.id, "replicate_custom_input", "{}")

    /** Only changed in development builds, to test against a local fake server. */
    val replicateBaseUrl = prefs.string(EnhanceFeature.id, "replicate_base_url", ReplicateUpscaler.DEFAULT_BASE_URL)

    /** `null` for automatic. */
    fun fixedStrength(): Strength? = Strength.entries.firstOrNull { it.name == strength.get() }

    fun replicateModel(): ReplicateModel = ReplicateModels.byId(replicateModel.get()) ?: ReplicateModel(
        id = ReplicateModel.CUSTOM,
        name = replicateCustomModel.get().ifBlank { "?" },
        model = replicateCustomModel.get().trim(),
        imageField = replicateCustomField.get().trim().ifEmpty { "image" },
        extraInput = replicateCustomInput.get().ifBlank { "{}" },
        license = "?",
        price = "?",
    )

    /** Settings for upscaling on Replicate, or `null` when pages are enhanced on the phone. */
    fun cloud(graph: PlusGraph): PageEnhancer.Cloud? {
        if (backend.get() != REPLICATE) return null
        val token = graph.secrets.get(REPLICATE_TOKEN)
        val upscaler = token?.let { ReplicateUpscaler(graph.httpClient, it, replicateBaseUrl.get()) }
        return PageEnhancer.Cloud(upscaler, replicateModel())
    }

    /** Models the current setting may use: in automatic mode, those of [ScanQuality.autoStrengths]. */
    fun models(): List<EnhanceModel> = (fixedStrength()?.let(::listOf) ?: ScanQuality.autoStrengths)
        .map(EnhanceModels.cugan::getValue)

    companion object {
        const val AUTO = "auto"
        const val DEVICE = "device"
        const val REPLICATE = "replicate"

        /** [plus.core.secret.SecretStore] id of the Replicate API token. */
        const val REPLICATE_TOKEN = "enhance_replicate"
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
        val backend by prefs.backend.collectAsState()
        val onDevice = backend != EnhancePreferences.REPLICATE
        val strength by prefs.strength.collectAsState()
        val models = remember(strength) { prefs.models() }
        val model = models.first()
        var downloadState by remember(models) {
            mutableStateOf<String?>(
                if (models.all(store::isDownloaded)) context.getString(R.string.plus_enhance_model_ready) else null,
            )
        }
        val gpuName = remember { runCatching { NativeUpscaler.gpuName }.getOrNull() }
        val lastRun = EnhanceFeature.lastRun
        val replicateModel by prefs.replicateModel.collectAsState()
        val cloudModelName = remember(replicateModel) { prefs.replicateModel().name }
        val hasToken = remember(backend) { graph.secrets.get(EnhancePreferences.REPLICATE_TOKEN) != null }

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
                        preference = prefs.backend,
                        entries = mapOf(
                            EnhancePreferences.DEVICE to stringResource(R.string.plus_enhance_backend_device),
                            EnhancePreferences.REPLICATE to stringResource(R.string.plus_enhance_backend_replicate),
                        ),
                        title = stringResource(R.string.plus_enhance_backend),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_enhance_replicate_settings),
                        subtitle = stringResource(
                            R.string.plus_enhance_replicate_summary,
                            cloudModelName,
                            stringResource(
                                if (hasToken) {
                                    R.string.plus_enhance_replicate_token_saved
                                } else {
                                    R.string.plus_enhance_replicate_token_missing
                                },
                            ),
                        ),
                        onClick = { navigator.push(EnhanceReplicateScreen()) },
                    ).takeIf { !onDevice },
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.strength,
                        entries = mapOf(
                            EnhancePreferences.AUTO to stringResource(R.string.plus_enhance_strength_auto),
                            Strength.Conservative.name to stringResource(R.string.plus_enhance_strength_conservative),
                            Strength.Light.name to stringResource(R.string.plus_enhance_strength_light),
                            Strength.Medium.name to stringResource(R.string.plus_enhance_strength_medium),
                            Strength.Strong.name to stringResource(R.string.plus_enhance_strength_strong),
                        ),
                        title = stringResource(R.string.plus_enhance_strength),
                        subtitle = "%s\n" + stringResource(R.string.plus_enhance_strength_summary),
                    ).takeIf { onDevice },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.whitenPaper,
                        title = stringResource(R.string.plus_enhance_whiten),
                        subtitle = stringResource(R.string.plus_enhance_whiten_summary),
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
                    ).takeIf { onDevice },
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_enhance_model) + ": " + model.name,
                        subtitle = stringResource(
                            R.string.plus_enhance_model_info,
                            model.license.name,
                            Formatter.formatShortFileSize(context, models.sumOf { it.downloadBytes }),
                        ) + (downloadState?.let { "\n$it" } ?: ""),
                    ).takeIf { onDevice },
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.plus_enhance_download),
                        onClick = {
                            downloadState = context.getString(R.string.plus_enhance_downloading)
                            scope.launch {
                                downloadState = try {
                                    models.forEach { store.get(it) }
                                    context.getString(R.string.plus_enhance_model_ready)
                                } catch (e: Exception) {
                                    context.getString(R.string.plus_enhance_download_failed, e.message)
                                }
                            }
                        },
                    ).takeIf { onDevice && !models.all(store::isDownloaded) },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.allowNonCommercial,
                        title = stringResource(R.string.plus_enhance_non_commercial),
                        subtitle = stringResource(R.string.plus_enhance_non_commercial_summary),
                    ).takeIf { onDevice && EnhanceModels.all.any { !it.license.commercialUse } },
                    lastRun?.let { Preference.PreferenceItem.InfoPreference(lastRunText(it)) },
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(if (onDevice) R.string.plus_enhance_how else R.string.plus_enhance_how_cloud),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun lastRunText(run: PageEnhancer.Run): String {
    val main = if (run.cloudModel != null) {
        stringResource(
            R.string.plus_enhance_last_run_cloud,
            run.width,
            run.height,
            run.millis / 1000f,
            run.cloudModel!!,
        )
    } else {
        stringResource(
            R.string.plus_enhance_last_run,
            run.width,
            run.height,
            run.millis / 1000f,
            stringResource(if (run.gpu) R.string.plus_enhance_on_gpu else R.string.plus_enhance_on_cpu),
            run.strength?.let { stringResource(strengthName(it)).lowercase() } ?: "",
        )
    }
    val jpeg = run.jpegQuality?.let { stringResource(R.string.plus_enhance_last_run_jpeg, it) } ?: ""
    val whitened = if (run.whitened) stringResource(R.string.plus_enhance_last_run_whitened) else ""
    return main + jpeg + whitened
}

private fun strengthName(strength: Strength) = when (strength) {
    Strength.Conservative -> R.string.plus_enhance_strength_conservative
    Strength.Light -> R.string.plus_enhance_strength_light
    Strength.Medium -> R.string.plus_enhance_strength_medium
    Strength.Strong -> R.string.plus_enhance_strength_strong
}
