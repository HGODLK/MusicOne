package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf

/** 只下发稳定的播放动作，补充音源不订阅播放器的逐帧状态。 */
internal class LibraryPlaybackActions(val track: (MusicTrack) -> Unit, val playlist: (MusicPlaylist) -> Unit)

internal val LocalLibraryPlaybackActions = staticCompositionLocalOf<LibraryPlaybackActions> {
    error("音乐库播放宿主尚未挂载")
}
