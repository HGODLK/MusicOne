package com.musicone.demo

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** 现有业务到通用系统展示接口的单向转换；平台适配不接触音源解析器。 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
internal fun playbackSystemPresentations(
    context: Context,
    runtime: MusicPlaybackRuntime,
    player: Player,
): Flow<SystemPlaybackPresentation?> {
    val library = MusicLibraryStore.get(context)
    val prepared = combine(
        runtime.state.map { it.currentTrack }.distinctUntilChanged(),
        library.state.map { it.lyricRevision }.distinctUntilChanged(),
    ) { track, revision -> track to revision }.mapLatest { (track, _) ->
        track ?: return@mapLatest null
        val offset = if (track.source.supportsOnlineLyricOffset) {
            withContext(Dispatchers.IO) { library.lyricAsset(track.id).second }
        } else 0L
        PreparedSystemPlayback(track.id, track.title, track.artists, track.artworkUrl, track.durationMs,
            systemLyricLinesForPlayback(track, offset))
    }
    return combine(prepared, runtime.playbackProgress, runtime.playbackActivity) { track, progress, activity ->
        if (track == null) null else {
            // 新曲取票、媒体切换期间仍保留准备状态，不能误发成停止并拆掉旧岛。
            val ready = progress.trackId == track.id && player.currentMediaItem?.mediaId == track.id
            SystemPlaybackPresentation(
            SystemPlaybackKey(track.id, progress.generation), track.title, track.artist, track.artworkUri,
            track.lyrics, if (ready) progress.positionMs else 0L, progress.sampledAtMs,
            if (ready) player.duration.takeIf { it > 0L } ?: track.durationMs else track.durationMs,
            ready && activity.trackId == track.id && activity.advancing && player.isPlaying,
            readyForPresentation = ready,
        )
        }
    }
}

/** 源别校准留在播放业务边界；本地已校准时间线不能再次叠加手动偏移。 */
internal fun systemLyricLinesForPlayback(track: MusicTrack, manualOffsetMs: Long): List<SystemLyricLine> {
    val offset = if (track.source.supportsOnlineLyricOffset) manualOffsetMs else 0L
    val lead = lyricDisplayPosition(0L, track.source)
    return track.lyrics.map { SystemLyricLine(it.timeMs + offset - lead, it.text, it.translation) }
}

private data class PreparedSystemPlayback(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val durationMs: Long,
    val lyrics: List<SystemLyricLine>,
)
