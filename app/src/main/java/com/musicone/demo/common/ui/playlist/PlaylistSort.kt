package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale

internal enum class PlaylistSort(val label: String) {
    ADDED_DESC("加入时间：新到旧"), ADDED_ASC("加入时间：旧到新"), NAME("歌曲名称：升序");
}

internal fun sortedPlaylistTracks(tracks: List<MusicTrack>, sort: PlaylistSort): List<MusicTrack> = when (sort) {
    // 接口原始顺序为加入时间倒序；切换时始终从原始列表计算。
    PlaylistSort.ADDED_DESC -> tracks
    PlaylistSort.ADDED_ASC -> tracks.reversed()
    PlaylistSort.NAME -> {
        val collator = Collator.getInstance(Locale.CHINA)
        tracks.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }
}

@Composable
internal fun PlaylistSortButton(sort: PlaylistSort, onSort: (PlaylistSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledIconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(when (sort) {
                PlaylistSort.NAME -> Icons.Default.SortByAlpha
                PlaylistSort.ADDED_ASC -> Icons.Default.ArrowUpward
                PlaylistSort.ADDED_DESC -> Icons.Default.ArrowDownward
            }, "排序，当前${sort.label}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            PlaylistSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) },
                    leadingIcon = { Icon(when (option) {
                        PlaylistSort.NAME -> Icons.Default.SortByAlpha
                        PlaylistSort.ADDED_ASC -> Icons.Default.ArrowUpward
                        PlaylistSort.ADDED_DESC -> Icons.Default.ArrowDownward
                    }, null) },
                    trailingIcon = { if (sort == option) Text("✓") },
                    onClick = { onSort(option); expanded = false })
            }
        }
    }
}
