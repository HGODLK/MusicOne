package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 只给补充音源传稳定的播放回调，避免它订阅播放器状态跟着高频重组。 */
internal class LibraryPlaybackActions(val track: (MusicTrack) -> Unit, val playlist: (MusicPlaylist) -> Unit)

internal val LocalLibraryPlaybackActions = staticCompositionLocalOf<LibraryPlaybackActions> {
    error("音乐库播放宿主尚未挂载")
}
