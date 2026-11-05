package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 确认单与执行回执使用相同字段明细，批量操作先呈现五组并保留完整展开入口。 */
@Composable
internal fun AiChangeDetails(groups: List<AiChangeGroupUi>, summary: String? = null) {
    var expanded by remember(groups) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        summary?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        (if (expanded) groups else groups.take(5)).forEach { group ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleSmall)
                group.fields.forEach { field ->
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(field.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("原：${field.before.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("现：${field.after.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
        if (groups.size > 5) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起明细" else "展开全部 ${groups.size} 项")
        }
    }
}
