package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 请求选项沿用设置页表单，按服务保存，不依赖模型列表是否上报能力。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiRequestSettings(provider: AiProviderUiState, enabled: Boolean, onAction: (AiUiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("请求设置", style = MaterialTheme.typography.titleMedium)
        if (provider.id == "other") {
            OutlinedTextField(provider.contextTokens, { onAction(AiUiAction.ContextTokens(it)) },
                Modifier.fillMaxWidth(), label = { Text("上下文容量（token）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = enabled,
                isError = provider.contextTokens.toIntOrNull()?.let { it > 0 } != true)
            OutlinedTextField(provider.maxOutputTokens, { onAction(AiUiAction.OutputTokens(it)) },
                Modifier.fillMaxWidth(), label = { Text("单次输出上限（token）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = enabled,
                isError = provider.maxOutputTokens.toIntOrNull()?.let { it > 0 && it < (provider.contextTokens.toIntOrNull() ?: 0) } != true)
        } else Text("上下文容量 1000000 token · 单次输出上限 384000 token", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("启用思考", Modifier.weight(1f))
            Switch(provider.thinkingEnabled, { onAction(AiUiAction.Thinking(it)) }, enabled = enabled)
        }
        if (provider.thinkingEnabled) {
            val levels = if (provider.id == "deepseek") listOf("low", "high", "max") else listOf("low", "medium", "high")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { level -> FilterChip(provider.reasoningEffort == level,
                    { onAction(AiUiAction.ReasoningEffort(level)) }, label = { Text(level) }, enabled = enabled) }
            }
            if (provider.id == "other") OutlinedTextField(provider.reasoningEffort,
                { onAction(AiUiAction.ReasoningEffort(it)) }, Modifier.fillMaxWidth(),
                label = { Text("自定义思考强度") }, singleLine = true, enabled = enabled,
                supportingText = { Text("按服务支持的值发送 reasoning_effort，不自动替换档位。") })
        }
        provider.requestOptionsError()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("思考与输出参数按配置发送；服务不支持时会报错，不自动降低额度或改用其他档位。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
