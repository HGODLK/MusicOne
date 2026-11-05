package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiConfigurationScreen(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit, modifier: Modifier) {
    val provider = state.providers.firstOrNull { it.id == state.providerId }
    var reveal by remember(state.providerId) { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var modelAnchor by remember { mutableStateOf(Rect.Zero) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("选择服务商，验证密钥后获取其当前模型。各服务的密钥与模型分别保存。网页搜索统一使用 DeepSeek。", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.providers.forEach { item -> FilterChip(selected = state.providerId == item.id,
                onClick = { onAction(AiUiAction.SelectProvider(item.id)) }, enabled = !state.running, label = { Text(item.name) }) }
        }
        if (provider != null) {
            if (provider.id == "other") {
                val endpointError = provider.baseUrl.takeIf { it.isNotBlank() }?.let { runCatching { aiNormalizeBaseUrl(it) }.exceptionOrNull()?.message }
                OutlinedTextField(value = provider.baseUrl, onValueChange = { onAction(AiUiAction.EditBaseUrl(it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("API 基址") }, singleLine = true,
                    enabled = !state.running && !provider.refreshing, isError = endpointError != null,
                    supportingText = { Text(endpointError ?: "填写服务提供的基址，例如 https://example.com/v1") })
                OutlinedTextField(value = provider.modelInput, onValueChange = { onAction(AiUiAction.EditModel(it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true,
                    enabled = !state.running && !provider.refreshing,
                    supportingText = { Text("可直接填写模型 ID，也可获取模型列表后选择。") })
            }
            Text(if (state.webEnabled) "网页搜索已启用，使用 DeepSeek。" else "网页搜索不可用，请先配置并验证 DeepSeek API Key。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = provider.key, onValueChange = { onAction(AiUiAction.EditKey(it)) },
                modifier = Modifier.fillMaxWidth(), label = { Text(if (provider.id == "other") "API Key（可选）" else "${provider.name} API Key") }, singleLine = true,
                enabled = !state.running && !provider.refreshing,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { reveal = !reveal }, enabled = !state.running && !provider.refreshing) {
                    Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (reveal) "隐藏密钥" else "显示密钥")
                } })
            Button(onClick = { onAction(AiUiAction.RefreshModels) }, enabled = !state.running && (provider.id == "other" || provider.key.isNotBlank()) && !provider.refreshing &&
                (provider.id != "other" || (provider.modelInput.isNotBlank() && runCatching { aiNormalizeBaseUrl(provider.baseUrl) }.isSuccess))) {
                if (provider.refreshing) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (provider.id == "other") "验证当前模型" else if (provider.models.isEmpty()) "验证并获取模型" else "重新验证并刷新模型")
            }
            if (provider.id == "other") OutlinedButton(onClick = { onAction(AiUiAction.FetchModels) },
                enabled = !state.running && !provider.refreshing && runCatching { aiNormalizeBaseUrl(provider.baseUrl) }.isSuccess) {
                Text("获取模型列表")
            }
            provider.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (provider.models.isNotEmpty()) {
                OutlinedButton(onClick = { choosingModel = true }, enabled = !state.running && !provider.refreshing,
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { modelAnchor = it.boundsInRoot() }) {
                    Text(provider.selectedModel.ifBlank { "选择聊天模型" })
                }
                Text("仅显示可用于文字对话的模型。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AiRequestSettings(provider, !state.running && !provider.refreshing, onAction)
            Button(onClick = { onAction(AiUiAction.SaveConfiguration) },
                enabled = !state.running && provider.verified && provider.selectedModel in provider.models && !provider.refreshing && provider.requestOptionsError() == null) { Text("保存并开始聊天") }
        }
        HorizontalDivider()
        AiPersonalityEditor(state, onAction)
        Spacer(Modifier.height(20.dp))
    }
    // 与歌单歌曲菜单一致：贴按钮弹出玻璃菜单，行尾勾标出当前模型。
    if (choosingModel && provider != null) AiGlassMenu(modelAnchor, "选择聊天模型", onDismissRequest = { choosingModel = false }) {
        provider.models.filterNot { it.contains("tts", true) || it.contains("asr", true) }.forEach { model ->
            SongMenuRow(model, !state.running && !provider.refreshing,
                trailing = { if (model == provider.selectedModel) Icon(Icons.Default.Check, contentDescription = "当前模型") }) {
                onAction(AiUiAction.SelectModel(model)); choosingModel = false
            }
        }
    }
}
