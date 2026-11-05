package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.*

/** 模板和音源组合页面共用的数据控件，不持有解析或导入业务模型。 */
@Composable
internal fun SupplementalLinkInput(value: String, resolving: Boolean, onChange: (String) -> Unit, onResolve: () -> Unit) {
    LibrarySettingsGroup("通过链接导入") {
        Text("粘贴此音源支持的内容链接或编号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LibraryFormField("内容链接或编号", value, onChange, !resolving)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LibraryActionButton(if (resolving) "正在解析…" else "解析内容", onResolve,
                icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = value.isNotBlank() && !resolving)
        }
    }
}

@Composable
internal fun SupplementalContentSummary(content: SourceContent) {
    LibrarySettingsGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteArtwork(content.artwork.url.orEmpty(), content.artwork.startArgb, content.artwork.endArgb, content.artwork.mark,
                Modifier.size(72.dp), 24.sp, RoundedCornerShape(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(content.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(content.author?.name ?: content.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SupplementalChildSelection(children: List<SourceChildItem>, selected: Set<TrackIdentity>, enabled: Boolean,
    onToggle: (TrackIdentity) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit) {
    if (children.isEmpty()) return
    Text("${children.size} 个子项目 · 已选 ${selected.size} 个", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onSelectAll, enabled = enabled) { Text("全选") }
        TextButton(onClick = onClear, enabled = enabled) { Text("清空") }
    }
    children.forEach { child -> key(child.identity) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toggleable(child.identity in selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(child.identity) })
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(child.identity in selected, onCheckedChange = null, enabled = enabled)
            Text(child.index.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(child.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
}

@Composable
internal fun SupplementalImportDestination(destinations: List<SourceLocalCollection>, selectedId: String?,
    onSelect: (SourceLocalCollection) -> Unit) {
    val menu = LocalPlaybackSettingsMenu.current
    val eligible = destinations.filter { it.acceptsManualTracks }
    val selected = eligible.firstOrNull { it.id == selectedId }
    val trigger = rememberMenuTriggerOrigin()
    LaunchedEffect(destinations, selectedId) { if (selected == null) eligible.firstOrNull()?.let(onSelect) }
    LibraryActionRow("添加到：${selected?.title.orEmpty()}", enabled = menu != null && eligible.isNotEmpty(),
        modifier = Modifier.menuTriggerOrigin(trigger)) {
        menu?.open("选择导入目标", trigger.origin, supplementalDestinationOptions(destinations, selectedId, onSelect))
    }
}

/** 导入目标菜单项：只列出接受手动添加的本地歌单。 */
internal fun supplementalDestinationOptions(destinations: List<SourceLocalCollection>, selectedId: String?,
    onSelect: (SourceLocalCollection) -> Unit): List<PlaybackSettingsMenuOption> =
    destinations.filter { it.acceptsManualTracks }.map { target ->
        PlaybackSettingsMenuOption(target.title, target.id == selectedId) { onSelect(target) }
    }

@Composable
internal fun SupplementalImportActions(selectedCount: Int, hasChildren: Boolean, busy: Boolean,
    canImport: Boolean, canPlay: Boolean, onImport: () -> Unit, onPlay: () -> Unit,
    showImport: Boolean = true, showPlay: Boolean = true) {
    val enabled = !busy && (!hasChildren || selectedCount > 0)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showImport) LibraryActionButton("加入歌单${if (hasChildren) "（$selectedCount）" else ""}", onImport, enabled = enabled && canImport)
        if (showPlay) LibraryActionButton("播放所选", onPlay, icon = Icons.Default.PlayArrow, enabled = enabled && canPlay, primary = false)
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}
