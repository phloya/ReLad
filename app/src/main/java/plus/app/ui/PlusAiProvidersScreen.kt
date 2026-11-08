package plus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import plus.app.Plus
import plus.core.ai.AiClients
import plus.core.ai.AiProviderConfig
import plus.core.ai.AiProviderType
import plus.core.ai.AiRequest
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.collectAsState
import java.util.UUID
import androidx.compose.ui.res.stringResource as androidStringResource

/** List of AI providers the user added. */
class PlusAiProvidersScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { Plus.graph(context) }
        val providersJson by graph.preferences.aiProvidersJson.collectAsState()
        val providers = remember(providersJson) { graph.preferences.aiProviders() }

        Scaffold(
            topBar = {
                AppBar(title = androidStringResource(R.string.plus_ai_providers), navigateUp = navigator::pop)
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text(androidStringResource(R.string.plus_ai_provider_add)) },
                    icon = { Icon(MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { navigator.push(PlusAiProviderEditScreen(providerId = null)) },
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item {
                    Text(
                        text = androidStringResource(R.string.plus_ai_providers_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
                items(providers, key = { it.id }) { provider ->
                    TextPreferenceWidget(
                        title = provider.name,
                        subtitle = "${typeLabel(provider.type)} · ${provider.model}",
                        onPreferenceClick = { navigator.push(PlusAiProviderEditScreen(provider.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: AiProviderType): String = androidStringResource(
    when (type) {
        AiProviderType.OpenAiCompatible -> R.string.plus_ai_provider_type_openai
        AiProviderType.Gemini -> R.string.plus_ai_provider_type_gemini
        AiProviderType.Anthropic -> R.string.plus_ai_provider_type_anthropic
    },
)

/** Adds or edits one provider. The key is written to the encrypted store, never shown back. */
class PlusAiProviderEditScreen(private val providerId: String?) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { Plus.graph(context) }
        val scope = rememberCoroutineScope()
        val existing = remember { providerId?.let { graph.aiProvider(it) } }
        val hasSavedKey = remember { existing != null && graph.secrets.get(existing.id) != null }

        var name by remember { mutableStateOf(existing?.name ?: "OpenRouter") }
        var type by remember { mutableStateOf(existing?.type ?: AiProviderType.OpenAiCompatible) }
        var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: type.defaultBaseUrl) }
        var model by remember { mutableStateOf(existing?.model ?: "") }
        var key by remember { mutableStateOf("") }
        var checkResult by remember { mutableStateOf<String?>(null) }

        fun current(id: String = existing?.id ?: "") = AiProviderConfig(
            id = id,
            name = name.trim().ifEmpty { "AI" },
            type = type,
            baseUrl = baseUrl.trim(),
            model = model.trim(),
        )
        val canSave = model.isNotBlank() && baseUrl.isNotBlank()

        Scaffold(
            topBar = {
                AppBar(title = androidStringResource(R.string.plus_ai_provider_edit), navigateUp = navigator::pop)
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(androidStringResource(R.string.plus_ai_provider_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(androidStringResource(R.string.plus_ai_provider_type), style = MaterialTheme.typography.labelLarge)
                AiProviderType.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = type == option,
                                role = Role.RadioButton,
                                onClick = {
                                    // Switching API also switches the address, unless the user typed their own
                                    if (baseUrl.isBlank() ||
                                        baseUrl == type.defaultBaseUrl
                                    ) {
                                        baseUrl = option.defaultBaseUrl
                                    }
                                    type = option
                                },
                            ),
                    ) {
                        RadioButton(selected = type == option, onClick = null)
                        Text(typeLabel(option), modifier = Modifier.padding(start = MaterialTheme.padding.small))
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(androidStringResource(R.string.plus_ai_provider_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(androidStringResource(R.string.plus_ai_provider_model)) },
                    supportingText = { Text(androidStringResource(R.string.plus_ai_provider_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(androidStringResource(R.string.plus_ai_provider_key)) },
                    supportingText = if (hasSavedKey) {
                        { Text(androidStringResource(R.string.plus_ai_provider_key_saved)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                val checking = androidStringResource(R.string.plus_ai_provider_checking)
                val okText = androidStringResource(R.string.plus_ai_provider_check_ok)
                val failedText = androidStringResource(R.string.plus_ai_provider_check_failed)
                OutlinedButton(
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        checkResult = checking
                        val apiKey = key.ifBlank { existing?.let { graph.secrets.get(it.id) }.orEmpty() }
                        val client = AiClients.create(current(), apiKey, graph.httpClient)
                        scope.launch {
                            checkResult = try {
                                val answer = client.send(
                                    AiRequest(
                                        system = null,
                                        prompt = "Reply with the single word OK.",
                                        maxOutputTokens = 64,
                                    ),
                                ).text.orEmpty().trim().take(60)
                                okText.format(answer)
                            } catch (e: Exception) {
                                failedText.format(e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                ) { Text(androidStringResource(R.string.plus_ai_provider_check)) }
                checkResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Button(
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val id = existing?.id ?: UUID.randomUUID().toString()
                        if (key.isNotBlank()) graph.secrets.put(id, key.trim())
                        val others = graph.preferences.aiProviders().filterNot { it.id == id }
                        graph.preferences.setAiProviders(others + current(id))
                        navigator.pop()
                    },
                ) { Text(androidStringResource(R.string.plus_ai_provider_save)) }

                if (existing != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            graph.secrets.remove(existing.id)
                            graph.preferences.setAiProviders(
                                graph.preferences.aiProviders().filterNot {
                                    it.id ==
                                        existing.id
                                },
                            )
                            navigator.pop()
                        },
                    ) { Text(androidStringResource(R.string.plus_ai_provider_delete)) }
                }
            }
        }
    }
}
