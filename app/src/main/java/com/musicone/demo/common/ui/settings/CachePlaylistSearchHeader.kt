package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

/** 搜索固定在分组标题右侧，直接复用歌单搜索按钮的展开、输入和关闭动画。 */
@Composable
internal fun CachePlaylistSearchHeader(search: PlaylistSearchState) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("其他歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        PlaylistFloatingTools(search, false, {}, null, Rect.Zero, 0.dp, { 1f }, modifier = Modifier.widthIn(max = 212.dp),
            showLocate = false, inlinePlacement = true, searchLabel = "搜索歌单")
    }
}
