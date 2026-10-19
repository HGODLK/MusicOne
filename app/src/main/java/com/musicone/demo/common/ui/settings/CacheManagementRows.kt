package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun CachePlaylistCard(playlist: MusicPlaylist, state: MusicCacheState, onOpen: () -> Unit, onPlay: () -> Unit) {
    val complete = playlist.tracks.count { state.statuses[it.cacheIdentity()]?.startsWith("已缓存") == true }
    val subtitle = "${if (playlist.source == MusicSource.LOCAL) "" else playlist.source.label + " · "}$complete/${maxOf(playlist.count, playlist.tracks.size)}首已缓存"
    QqPlaylistCard(playlist.copy(subtitle = subtitle), onOpen, onPlay,
        Modifier.fillMaxWidth(), width = 1280.dp, sharedTransition = false, sourceKey = "cache:${cachePlaylistKey(playlist)}")
}

@Composable
internal fun CacheSongRow(track: MusicTrack, state: MusicCacheState, onPlay: (MusicTrack) -> Unit,
    onCache: (MusicTrack) -> Unit, onDelete: (MusicTrack) -> Unit, currentTrackId: String?, playing: Boolean) {
    val menu = LocalPlaybackSettingsMenu.current
    val status = state.statuses[track.cacheIdentity()].orEmpty()
    val audio = state.audioDetails[track.cacheIdentity()]
    val detail = if (audio == null) status else {
        val qualities = audio.qualities.map { it.displayLabel(track.source) } + if (audio.hasUnknownQuality) listOf("音质未知") else emptyList()
        val size = if (audio.bytes >= 1_048_576L) cacheSizeLabel(audio.bytes)
            else if (audio.bytes >= 1024L) "%.1f KB".format(audio.bytes / 1024.0) else "${audio.bytes} B"
        listOf(qualities.joinToString(" / "), size, status).filter(String::isNotBlank).joinToString(" · ")
    }
    val reason = state.blocked[track.cacheIdentity()]
    PlaylistTrackRow(track, current = currentTrackId == track.id, playing = currentTrackId == track.id && playing,
        favorite = false, pendingRemoval = false, onClick = { onPlay(track) }, onFavorite = {}, modifier = Modifier.fillMaxWidth(),
        supportingContent = {
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 18.dp))
        }, onMore = { bounds ->
            menu?.open(track.title, bounds.center, listOf(
                PlaybackSettingsMenuOption("立即播放", false) { onPlay(track) },
                PlaybackSettingsMenuOption("缓存歌曲", false, enabled = reason == null && state.busyPlaylist == null,
                    description = reason) { onCache(track) },
                PlaybackSettingsMenuOption("删除缓存", false,
                    enabled = state.cachedTracks.any { it.cacheIdentity() == track.cacheIdentity() }) { onDelete(track) },
            ))
        })
}

@Composable
internal fun CachePlaylistControls(playlist: MusicPlaylist, state: MusicCacheState, manager: MusicCacheManager,
    onDelete: (MusicPlaylist) -> Unit) {
    val busy = state.busyPlaylist == cachePlaylistKey(playlist)
    val reason = if (playlist.tracks.isNotEmpty()) playlist.tracks.let { tracks ->
        if (tracks.any { it.cacheIdentity() !in state.blocked }) null else state.blocked[tracks.first().cacheIdentity()]
    } else when {
        playlist.isPrimaryCachePlaylist && state.appliedLimits.maximum == 0L -> "新增缓存已关闭"
        !playlist.isPrimaryCachePlaylist && state.appliedLimits.total == CacheCapacity.OFF -> "总缓存已关闭"
        !playlist.isPrimaryCachePlaylist && state.usage.primary >= state.appliedLimits.total.bytes -> "一级歌曲已占满总缓存额度"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryActionButton(if (busy) "停止缓存" else "缓存歌单", { if (busy) manager.cancel() else manager.cache(playlist) },
                enabled = busy || (state.busyPlaylist == null && reason == null))
            LibraryActionButton("删除缓存", { onDelete(playlist) }, primary = false, enabled = playlist.tracks.isNotEmpty())
        }
        if (!busy && reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CacheClearConfirmation(mode: CacheClearMode?, onDismiss: () -> Unit, onConfirm: (CacheClearMode) -> Unit) {
    if (mode == null) return
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${mode.title}？") },
        text = { Text(mode.description) },
        confirmButton = { TextButton(onClick = { onConfirm(mode); onDismiss() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
