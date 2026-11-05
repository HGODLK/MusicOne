package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** 复用设置页标题和原歌单搜索控件，查询仅交给本地会话存储。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AiHistoryScreen(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit, onBack: () -> Unit) {
    val search = rememberPlaylistSearchState("ai-conversation-history")
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val conversations = if (state.historyQuery.isBlank()) state.conversations else state.historyResults
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    BackHandler(selecting && !confirming) { selected = emptySet() }
    LaunchedEffect(state.conversations) { selected = selected.intersect(state.conversations.map { it.id }.toSet()) }
    LaunchedEffect(search.query) { onAction(AiUiAction.SearchHistory(search.query)) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(if (selecting) "已选择 ${selected.size} 项" else "历史对话",
            { if (selecting) selected = emptySet() else onBack() }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                TextButton(onClick = { selected = conversations.map { it.id }.toSet() }, modifier = Modifier.weight(1f)) { Text("全选") }
                TextButton(onClick = { confirming = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { selected = emptySet() }) { Text("取消") }
            } else {
            TextButton(onClick = { onAction(AiUiAction.NewConversation) }, modifier = Modifier.weight(1f)) {
                Text("新建对话")
            }
            PlaylistFloatingTools(search, false, {}, null, Rect.Zero, 0.dp, { 1f },
                Modifier.widthIn(max = 236.dp), showLocate = false, inlinePlacement = true, searchLabel = "搜索历史对话")
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.historySearching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (conversations.isEmpty() && !state.historySearching) item {
                Text(if (state.historyQuery.isBlank()) "还没有历史对话" else "没有找到匹配的对话", Modifier.padding(vertical = 24.dp))
            }
            items(conversations, key = { it.id }) { conversation ->
                ListItem(headlineContent = { Text(conversation.title.ifBlank { "新会话" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (conversation.snippet.isNotBlank()) Text(conversation.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(if (conversation.updatedAt > 0L) dateFormat.format(Date(conversation.updatedAt)) else "发送时间未记录",
                            style = MaterialTheme.typography.labelSmall)
                    } }, trailingContent = {
                        if (selecting) Checkbox(checked = conversation.id in selected, onCheckedChange = { toggle(conversation.id) })
                        else if (conversation.id == state.currentConversationId) Text("当前", color = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.combinedClickable(onLongClick = { toggle(conversation.id) }, onClick = {
                        if (selecting) toggle(conversation.id) else {
                        onAction(AiUiAction.SelectConversation(conversation.id))
                        onAction(AiUiAction.CloseHistory)
                        }
                    }))
            }
        }
    }
    if (confirming) AiGlassDialog(onDismissRequest = { confirming = false },
        title = { Text("删除 ${selected.size} 个对话？") },
        text = { Text("对话记录删除后无法恢复。已保存的歌单和音乐偏好不受影响。") },
        confirmButton = { TextButton(onClick = {
            onAction(AiUiAction.DeleteConversations(selected))
            confirming = false
            selected = emptySet()
        }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } })
}
