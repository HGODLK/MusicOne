package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 历史使用独立二级页，顶栏只保留入口与新建会话。 */
@Composable
internal fun AiConversationControls(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(state.conversations.firstOrNull { it.id == state.currentConversationId }?.title?.takeIf { it.isNotBlank() }
            ?: "MusicOne AI", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium)
        Box {
            IconButton(onClick = { menuExpanded = true },
                modifier = Modifier.size(48.dp).onGloballyPositioned { anchor = it.boundsInRoot() }) {
                Icon(Icons.Rounded.MoreVert, "对话操作")
            }
        }
    }
    // 与歌单歌曲菜单一致：贴按钮弹出玻璃菜单，承接原有两个会话操作。
    if (menuExpanded) AiGlassMenu(anchor, "对话操作", onDismissRequest = { menuExpanded = false }) {
        SongMenuRow("新会话", true) { menuExpanded = false; onAction(AiUiAction.NewConversation) }
        SongMenuRow("历史对话", true) { menuExpanded = false; onAction(AiUiAction.ShowHistory) }
    }
}
