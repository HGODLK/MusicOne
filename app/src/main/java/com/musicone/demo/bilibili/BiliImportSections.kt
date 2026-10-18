package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 分 P 选择仍使用原有解析、目标歌单与导入动作，只整理封面和表单层级。 */
@Composable
internal fun BiliLinkSection(state: BiliLibraryUiState, viewModel: BiliLibraryViewModel, showInput: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showInput) BiliLinkEntry(state, viewModel)
        if (state.resolvingLink) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.videoPages.isNotEmpty()) {
            state.currentVideoDetails?.let { details ->
                LibrarySettingsGroup {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteArtwork(details.cover, 0L, 0L, "", Modifier.size(72.dp), 24.sp, RoundedCornerShape(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(details.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(details.upName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            LibrarySettingsGroup {
                Text("${state.videoPages.size} 个分 P · 已选 ${state.selectedCids.size} 个", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = viewModel::selectAllCids) { Text("全选") }
                    TextButton(onClick = viewModel::clearCidSelection) { Text("清空") }
                }
                state.videoPages.forEach { part ->
                    val selected = part.cid in state.selectedCids
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .toggleable(selected, role = Role.Checkbox, onValueChange = { viewModel.toggleCidSelection(part.cid) })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(selected, onCheckedChange = null)
                        Text("P${part.page}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(part.part, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                BiliImportTarget(state, viewModel)
                LibraryActionButton("加入歌单（${state.selectedCids.size}）", { viewModel.importSelectedParts(state.targetPlaylistId) },
                    enabled = state.selectedCids.isNotEmpty() && !state.busy)
            }
        }
    }
}
