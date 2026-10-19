package com.musicone.demo

import androidx.compose.material3.*
import androidx.compose.runtime.*

/** 复用歌单多选工具栏，只替换批量动作；确认框关闭后保持列表位置。 */
@Composable
internal fun CachePlaylistSelectionToolbar(playlist: MusicPlaylist, manager: MusicCacheManager,
    selection: PlaylistMultiSelection) {
    var deleting by remember { mutableStateOf<List<MusicTrack>?>(null) }
    PlaylistSelectionToolbar(loadAll = { manager.selectionTracks(playlist) }, onDeleteCache = { deleting = it })
    deleting?.let { tracks ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${tracks.size} 首歌曲的缓存？") },
            text = { Text("只删除共用音频缓存，不删除歌单、收藏或本地音乐原文件。同曲在其他歌单中也会变为未缓存。") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                selection.close()
                manager.removeTracks(tracks)
            }) { Text("删除缓存") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
