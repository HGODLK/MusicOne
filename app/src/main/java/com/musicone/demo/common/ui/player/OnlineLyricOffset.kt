package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val MusicSource.supportsOnlineLyricOffset: Boolean
    get() = this == MusicSource.QQ || this == MusicSource.KUGOU || this == MusicSource.NETEASE

internal fun Modifier.onlineLyricOffset(track: MusicTrack, enabled: Boolean): Modifier =
    if (track.source.supportsOnlineLyricOffset) {
        playerRelatedInformation(track, enabled, lyricOffsetMenu = true)
    } else this

/** 在线音源只移动整行时间，逐字时间轴保持原样。 */
@Composable
internal fun rememberOnlineLyricLines(track: LyricPresentation): List<TimedLyric> {
    val ui = LocalMusicLibraryUi.current
    val revision = ui.snapshot.lyricRevision
    val offset by produceState(0L, track.id, track.source, revision) {
        value = if (track.source.supportsOnlineLyricOffset) {
            withContext(Dispatchers.IO) { ui.store.lyricAsset(track.id).second }
        } else 0L
    }
    return remember(track.lyrics, offset) {
        offsetOnlineLyricLines(track.lyrics, offset)
    }
}

internal fun offsetOnlineLyricLines(lines: List<TimedLyric>, offsetMs: Long): List<TimedLyric> =
    if (offsetMs == 0L) lines else lines.map { it.copy(timeMs = it.timeMs + offsetMs) }

/** 普通曲名菜单不能提供偏移，必须由已展开歌词的曲名入口打开。 */
internal fun onlineLyricOffsetActionsVisible(fromLyricsTitle: Boolean, hasLyrics: Boolean): Boolean =
    fromLyricsTitle && hasLyrics
