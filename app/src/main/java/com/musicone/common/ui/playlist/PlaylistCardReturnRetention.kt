package com.musicone

import androidx.compose.runtime.*

/** 收藏状态正常同步，来源卡片保留到封面归位和信息淡入全部结束。 */
@Composable
internal fun rememberPlaylistReturnCards(
    playlists: List<MusicPlaylist>,
    accountKey: String,
    sourceKeyPrefix: String,
): List<MusicPlaylist> {
    var previous by remember(accountKey, sourceKeyPrefix) { mutableStateOf(playlists) }
    // 归位时仍保留 activeSourceKey，卡片信息淡入完成后才会清空。
    val activeSourceKey = LocalPlaylistCardTransition.current?.activeSourceKey
    val presented = retainReturningPlaylist(playlists, previous, activeSourceKey, sourceKeyPrefix)
    SideEffect { previous = presented }
    return presented
}

internal fun retainReturningPlaylist(
    current: List<MusicPlaylist>,
    previous: List<MusicPlaylist>,
    activeSourceKey: String?,
    sourceKeyPrefix: String,
): List<MusicPlaylist> {
    if (activeSourceKey == null) return current
    val index = previous.indexOfFirst { "$sourceKeyPrefix${it.id}" == activeSourceKey }
    if (index < 0) return current
    val held = previous[index]
    val latest = current.firstOrNull { it.id == held.id && it.source == held.source } ?: held
    // 取消后又恢复收藏也保留原槽位，其余歌单仍接收正常更新。
    return current.filterNot { it.id == held.id && it.source == held.source }.toMutableList().apply {
        add(index.coerceAtMost(size), latest)
    }
}
