package plus.app.enhance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import plus.app.Plus
import plus.app.PlusFeatures
import plus.enhance.cloud.ReplicateModel
import plus.enhance.cloud.ReplicateModels
import plus.enhance.cloud.ReplicateUpscaler
import tachiyomi.presentation.core.components.material.padding

/** Replicate token and model for enhancing pages in the cloud. */
class EnhanceReplicateScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { Plus.graph(context) }
        val prefs = remember { EnhancePreferences(graph) }
        val hasSavedToken = remember { graph.secrets.get(EnhancePreferences.REPLICATE_TOKEN) != null }

        var token by remember { mutableStateOf("") }
        var modelId by remember { mutableStateOf(prefs.replicateModel.get()) }
        var customModel by remember { mutableStateOf(prefs.replicateCustomModel.get()) }
        var customField by remember { mutableStateOf(prefs.replicateCustomField.get()) }
        var customInput by remember { mutableStateOf(prefs.replicateCustomInput.get()) }
        var baseUrl by remember { mutableStateOf(prefs.replicateBaseUrl.get()) }
        var checkResult by remember { mutableStateOf<String?>(null) }
        val custom = modelId == ReplicateModel.CUSTOM
        val canSave = !custom || customModel.isNotBlank()

        Scaffold(
            topBar = {
                AppBar(title = stringResource(R.string.plus_enhance_replicate_settings), navigateUp = navigator::pop)
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(stringResource(R.string.plus_enhance_replicate_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.plus_enhance_replicate_token)) },
                    supportingText = if (hasSavedToken) {
                        { Text(stringResource(R.string.plus_enhance_replicate_token_saved)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.plus_enhance_replicate_model), style = MaterialTheme.typography.labelLarge)
                ReplicateModels.all.forEach { preset ->
                    ModelOption(
                        selected = modelId == preset.id,
                        title = preset.name,
                        subtitle = stringResource(
                            R.string.plus_enhance_replicate_model_info,
                            preset.license,
                            preset.price,
                        ),
                        onClick = { modelId = preset.id },
                    )
                }
                ModelOption(
                    selected = custom,
                    title = stringResource(R.string.plus_enhance_replicate_custom),
                    subtitle = null,
                    onClick = { modelId = ReplicateModel.CUSTOM },
                )
                if (custom) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text(stringResource(R.string.plus_enhance_replicate_custom_model)) },
                        supportingText = { Text(stringResource(R.string.plus_enhance_replicate_custom_model_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = customField,
                        onValueChange = { customField = it },
                        label = { Text(stringResource(R.string.plus_enhance_replicate_custom_field)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text(stringResource(R.string.plus_enhance_replicate_custom_input)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (PlusFeatures.isDevBuild) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.plus_enhance_replicate_base_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val checking = stringResource(R.string.plus_enhance_replicate_checking)
                val okText = stringResource(R.string.plus_enhance_replicate_check_ok)
                val failedText = stringResource(R.string.plus_enhance_replicate_check_failed)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val apiToken = token.trim().ifEmpty {
                            graph.secrets.get(EnhancePreferences.REPLICATE_TOKEN).orEmpty()
                        }
                        checkResult = checking
                        scope.launch {
                            checkResult = try {
                                val upscaler = ReplicateUpscaler(graph.httpClient, apiToken, baseUrl.trim())
                                okText.format(upscaler.account())
                            } catch (e: Exception) {
                                failedText.format(e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.plus_enhance_replicate_check)) }
                checkResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Button(
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (token.isNotBlank()) graph.secrets.put(EnhancePreferences.REPLICATE_TOKEN, token.trim())
                        prefs.replicateModel.set(modelId)
                        prefs.replicateCustomModel.set(customModel.trim())
                        prefs.replicateCustomField.set(customField.trim())
                        prefs.replicateCustomInput.set(customInput.trim())
                        prefs.replicateBaseUrl.set(baseUrl.trim().ifEmpty { ReplicateUpscaler.DEFAULT_BASE_URL })
                        navigator.pop()
                    },
                ) { Text(stringResource(R.string.plus_enhance_replicate_save)) }
            }
        }
    }
}

@Composable
private fun ModelOption(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = MaterialTheme.padding.small)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
