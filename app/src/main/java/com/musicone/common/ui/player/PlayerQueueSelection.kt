package com.musicone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 多选时队列行右侧的“更多”原位换成勾选框，左侧序号与正在播放标记不变。 */
@Composable
internal fun QueueRowTrailing(track: MusicTrack, interactive: Boolean, more: @Composable () -> Unit) {
    val selection = LocalPlaylistMultiSelection.current
    AnimatedContent(selection?.active == true,
        transitionSpec = { fadeIn(musicMotion(200)) togetherWith fadeOut(musicMotion(140)) },
        contentAlignment = Alignment.Center, label = "队列行多选勾选") { selecting ->
        if (selecting && selection != null) {
            val checked = selection.key(track) in selection.ids
            Box(Modifier.size(48.dp).toggleable(checked, enabled = interactive, role = Role.Checkbox,
                onValueChange = { selection.toggle(selection.key(track)) }), contentAlignment = Alignment.Center) {
                Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = "选择${track.title}", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        } else more()
    }
}

/** 标题右侧：平时是多选图标，多选时原位换成“全选／取消全选”和退出。 */
@Composable
internal fun PlayerQueueSelectionActions(selection: PlaylistMultiSelection, queue: List<MusicTrack>) {
    AnimatedContent(selection.active,
        transitionSpec = { fadeIn(musicMotion(220)) togetherWith fadeOut(musicMotion(160)) },
        contentAlignment = Alignment.CenterEnd, label = "队列多选入口") { selecting ->
        if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
            val ids = queue.mapTo(hashSetOf(), selection.key)
            val all = ids.isNotEmpty() && selection.ids.containsAll(ids)
            SelectionTextAction(if (all) "取消全选" else "全选", queue.isNotEmpty(), Color.White) {
                selection.ids = if (all) emptySet() else ids
            }
            IconButton(onClick = selection::close) { Icon(Icons.Rounded.Close, "退出多选", tint = Color.White) }
        } else IconButton(onClick = { if (queue.isNotEmpty()) selection.active = true }, enabled = queue.isNotEmpty()) {
            Icon(Icons.Rounded.Checklist, "多选", tint = Color.White.copy(alpha = if (queue.isNotEmpty()) 1f else .38f))
        }
    }
}

/** 抽屉底部操作条：添加到歌单（本地＋当前主音源）、移出队列；与抽屉同为深色实底。 */
@Composable
internal fun PlayerQueueSelectionBar(selection: PlaylistMultiSelection, queue: List<MusicTrack>,
    onRemove: (List<MusicTrack>) -> Unit) {
    val menu = LocalMainSourcePlaylistSongMenu.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    AnimatedVisibility(selection.active,
        enter = slideInVertically(musicMotion(260)) { it } + fadeIn(musicMotion(200)),
        exit = slideOutVertically(musicMotion(200)) { it } + fadeOut(musicMotion(160))) {
        val selected = queue.filter { selection.key(it) in selection.ids }
        val enabled = selected.isNotEmpty() && menu?.busy != true
        Row(Modifier.fillMaxWidth().background(Color(0xFF2D3034)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
                SelectionTextAction("添加到歌单", enabled && menu != null, Color.White) {
                    menu?.openQueueBatch(selected, anchor)
                }
            }
            SelectionTextAction("移出队列", enabled, Color.White) {
                selection.close()
                onRemove(selected)
            }
        }
    }
}
