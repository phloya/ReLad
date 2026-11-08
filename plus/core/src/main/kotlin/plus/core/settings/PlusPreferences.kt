package plus.core.settings

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import plus.core.ai.AiProviderConfig
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Re:Lad's settings. They live in Mihon's preference store with the `plus_` prefix, so Mihon's
 * backup and restore carry them. API keys are not here: see [plus.core.secret.SecretStore].
 */
class PlusPreferences(private val store: PreferenceStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether a feature is switched on. Features have ids like "translate", "enhance", "colorize". */
    fun featureEnabled(featureId: String): Preference<Boolean> =
        store.getBoolean("plus_feature_$featureId", false)

    /** The AI providers the user added, as JSON (see [aiProviders]). */
    val aiProvidersJson: Preference<String> = store.getString("plus_ai_providers", "[]")

    fun aiProviders(): List<AiProviderConfig> = runCatching {
        json.decodeFromString(ListSerializer(AiProviderConfig.serializer()), aiProvidersJson.get())
    }.getOrDefault(emptyList())

    fun setAiProviders(providers: List<AiProviderConfig>) {
        aiProvidersJson.set(json.encodeToString(ListSerializer(AiProviderConfig.serializer()), providers))
    }

    /** Settings for one feature, all prefixed `plus_<featureId>_`. */
    fun string(featureId: String, name: String, default: String = ""): Preference<String> =
        store.getString("plus_${featureId}_$name", default)

    fun boolean(featureId: String, name: String, default: Boolean = false): Preference<Boolean> =
        store.getBoolean("plus_${featureId}_$name", default)

    fun int(featureId: String, name: String, default: Int = 0): Preference<Int> =
        store.getInt("plus_${featureId}_$name", default)
}
