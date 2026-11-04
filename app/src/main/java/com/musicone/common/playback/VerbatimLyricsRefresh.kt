package com.musicone

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal fun needsVerbatimLyricsRefresh(track: MusicTrack): Boolean {
    if (track.lyrics.any { it.libraryTimeline }) return false
    val provider = com.musicone.sourceintegration.SourcePackageController.current()
        ?.running(track.source.id)?.entry?.lyricProvider ?: return false
    return needsVerbatimLyricsRefresh(track.lyrics, provider.parserVersion, provider.refreshPlainLyricsForVerbatim)
}

/** 设置开启时只升级当前稳定播放的歌曲，切歌加载仍由原请求入口负责。 */
internal fun CoroutineScope.observeVerbatimLyricsPreference(
    currentTrack: () -> MusicTrack?,
    refresh: (MusicTrack) -> Unit,
) = launch {
    snapshotFlow { ExperiencePreferences.options.verbatimLyricAnimation }.drop(1).collect { enabled ->
        if (enabled) currentTrack()?.takeIf(::needsVerbatimLyricsRefresh)?.let(refresh)
    }
}
