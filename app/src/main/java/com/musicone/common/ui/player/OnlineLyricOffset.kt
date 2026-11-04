package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val MusicSource.supportsOnlineLyricOffset: Boolean
    get() = supportsLyricOffset

internal fun Modifier.onlineLyricOffset(track: MusicTrack, enabled: Boolean): Modifier =
    if (enabled && (track.source.supportsOnlineLyricOffset || track.source.supportsManualLyrics)) {
        playerRelatedInformation(track, enabled, lyricOffsetMenu = true)
    } else this

/** 横屏共用标题热区，关闭歌词后恢复原歌曲资料菜单。 */
internal fun Modifier.landscapePlayerInformation(track: MusicTrack, enabled: Boolean, lyrics: Boolean): Modifier =
    if (lyrics) onlineLyricOffset(track, enabled) else playerRelatedInformation(track, enabled)

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
    if (offsetMs == 0L) lines else lines.map {
        if (it.libraryTimeline) it else it.copy(timeMs = it.timeMs + offsetMs)
    }

/** 普通资料菜单不提供偏移，歌词页眉及横屏信息入口使用歌词菜单资格。 */
internal fun onlineLyricOffsetActionsVisible(fromLyricsTitle: Boolean, hasLyrics: Boolean): Boolean =
    fromLyricsTitle && hasLyrics
