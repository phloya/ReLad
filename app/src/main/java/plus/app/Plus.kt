package plus.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import plus.core.ai.AiClient
import plus.core.ai.AiClients
import plus.core.ai.AiProviderConfig
import plus.core.pipeline.PageCache
import plus.core.pipeline.PageProcessingEngine
import plus.core.pipeline.PageProcessor
import plus.core.secret.KeystoreSecretStore
import plus.core.secret.SecretStore
import plus.core.settings.PlusPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.File
import java.util.concurrent.TimeUnit

/** Entry point to Re:Lad's shared objects. Created on first use, lives as long as the app. */
object Plus {

    @Volatile
    private var graph: PlusGraph? = null

    fun graph(context: Context): PlusGraph = graph ?: synchronized(this) {
        graph ?: PlusGraph(context.applicationContext).also { graph = it }
    }
}

class PlusGraph(val context: Context) {

    val preferenceStore = AndroidPreferenceStore(context)
    val preferences = PlusPreferences(preferenceStore)
    val secrets: SecretStore = KeystoreSecretStore(preferenceStore)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Separate from Mihon's client on purpose: Mihon's verbose network log prints request headers,
     * where Gemini and Claude keys travel, and its cookies and cache are for manga sites.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    val features: List<PlusFeature> = PlusFeatures.all.filter { !it.devOnly || PlusFeatures.isDevBuild }

    private val processors: Map<String, PageProcessor> by lazy {
        features.mapNotNull { feature -> feature.createProcessor(this)?.let { feature.id to it } }.toMap()
    }

    val pageCache = PageCache(File(context.cacheDir, "relad_pages"))

    val engine: PageProcessingEngine by lazy {
        PageProcessingEngine(
            processors = {
                features.filter { isEnabled(it) }.mapNotNull { processors[it.id] }
            },
            cache = pageCache,
            scope = scope,
        )
    }

    fun isAvailable(feature: PlusFeature): Boolean = feature.id in processors

    fun isEnabled(feature: PlusFeature): Boolean =
        isAvailable(feature) && preferences.featureEnabled(feature.id).get()

    fun anyEnabled(): Boolean = features.any { isEnabled(it) }

    fun aiProvider(id: String): AiProviderConfig? = preferences.aiProviders().firstOrNull { it.id == id }

    fun aiClient(provider: AiProviderConfig): AiClient =
        AiClients.create(provider, secrets.get(provider.id), httpClient)

    // Any Re:Lad setting change means pages may look different: let the reader reload them.
    // Kept in a field: SharedPreferences only holds listeners weakly.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("plus_")) engine.onSettingsChanged()
    }

    init {
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(settingsListener)
    }
}
