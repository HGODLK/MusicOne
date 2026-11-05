package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 人格只影响助手表达方式，实际执行权限仍由任务控制器约束。 */
@Composable
internal fun AiPersonalityEditor(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自定义人格", style = MaterialTheme.typography.titleMedium)
                Text("设置助手的语气、风格与音乐偏好", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.personalityEnabled,
                onCheckedChange = { onAction(AiUiAction.PersonalityEnabled(it)) }, enabled = !state.running,
                modifier = Modifier.semantics { contentDescription = "启用自定义人格" })
        }
        OutlinedTextField(state.personality, { onAction(AiUiAction.EditPersonality(it)) },
            Modifier.fillMaxWidth(), label = { Text("人格设定") },
            placeholder = { Text("例如：像一位熟悉独立音乐的朋友，回答简洁自然。") },
            minLines = 3, maxLines = 8, enabled = !state.running,
            supportingText = { Text(if (state.personalityEnabled) "已启用，将用于后续回复" else "关闭时使用默认人格，编辑内容会保留") })
        TextButton(onClick = { onAction(AiUiAction.RestoreDefaultPersonality) }, enabled = !state.running) { Text("恢复默认人格") }
    }
}
