package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** 菜单只拿稳定的队列操作，不订阅播放器每次进度和歌词更新。 */
internal class PlaybackQueueActions(
    val insertNext: (MusicTrack) -> Unit,
    val remove: (MusicTrack) -> Unit,
)

internal val LocalPlaybackQueueActions = staticCompositionLocalOf<PlaybackQueueActions?> { null }

@Composable
internal fun rememberPlaybackQueueActions(player: MusicPlaybackRuntime): PlaybackQueueActions =
    remember(player) { PlaybackQueueActions(player::insertTrackNext, player::removeTrackFromQueue) }
