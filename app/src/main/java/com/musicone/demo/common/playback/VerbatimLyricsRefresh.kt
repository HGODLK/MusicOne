package com.musicone.demo

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal fun needsVerbatimLyricsRefresh(track: MusicTrack): Boolean = track.source == MusicSource.QQ &&
    (!hasReusableLyrics(track, track.lyrics) || track.lyrics.none { it.words.isNotEmpty() })

/** 设置开启时只升级当前稳定播放的歌曲，切歌加载仍由原请求入口负责。 */
internal fun CoroutineScope.observeVerbatimLyricsPreference(
    currentTrack: () -> MusicTrack?,
    refresh: (MusicTrack) -> Unit,
) = launch {
    snapshotFlow { ExperiencePreferences.options.verbatimLyricAnimation }.drop(1).collect { enabled ->
        if (enabled) currentTrack()?.takeIf(::needsVerbatimLyricsRefresh)?.let(refresh)
    }
}
