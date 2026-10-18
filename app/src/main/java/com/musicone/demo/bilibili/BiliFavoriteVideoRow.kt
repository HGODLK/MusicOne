package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** 直接复用普通歌单歌曲行，只为更多菜单保留分 P 导入动作。 */
@Composable
internal fun BiliFavoriteVideoRow(track: MusicTrack, model: BiliLibraryViewModel, modifier: Modifier = Modifier,
    interactive: Boolean = true) {
    val playback = LocalLibraryPlaybackActions.current
    val menu = LocalPlaybackSettingsMenu.current
    PlaylistTrackRow(track, current = false, playing = false, favorite = false, pendingRemoval = false,
        onClick = { playback.track(track) }, onFavorite = {}, modifier = modifier, enabled = interactive,
        onMore = { bounds ->
            if (interactive && track.playable) menu?.open(track.title, bounds.center, listOf(
                PlaybackSettingsMenuOption("立即播放", false) { playback.track(track) },
                PlaybackSettingsMenuOption("选择分 P / 加入歌单", false,
                    enabled = !model.state.value.resolvingLink) { model.resolveLink(track.bilibiliBvid.orEmpty()) },
            ))
        })
}
