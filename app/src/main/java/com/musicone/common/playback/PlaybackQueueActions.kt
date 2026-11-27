package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** 给菜单用的队列操作回调，单独拆出来避免菜单跟着播放进度和歌词一起频繁重组。 */
internal class PlaybackQueueActions(
    val insertNext: (MusicTrack) -> Unit,
    val remove: (MusicTrack) -> Unit,
)

internal val LocalPlaybackQueueActions = staticCompositionLocalOf<PlaybackQueueActions?> { null }

@Composable
internal fun rememberPlaybackQueueActions(player: MusicPlaybackRuntime): PlaybackQueueActions =
    remember(player) { PlaybackQueueActions(player::insertTrackNext, player::removeTrackFromQueue) }
