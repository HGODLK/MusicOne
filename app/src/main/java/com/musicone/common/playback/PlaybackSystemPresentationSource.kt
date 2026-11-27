package com.musicone

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** 把播放运行时状态转成通用的系统展示数据（供桌面歌词、状态栏歌词等消费），与具体音源解耦。 */
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
        val timing = sourceLyricTiming(track)
        PreparedSystemPlayback(track.id, track.title, track.artists, track.artworkUrl, track.durationMs,
            systemLyricLinesForPlayback(track, offset, timing), timing.wordLeadMs)
    }
    val lyricStates = runtime.state.map { it.currentTrack?.id to it.lyricLoadState }.distinctUntilChanged()
    return combine(prepared, runtime.playbackProgress, runtime.playbackActivity, lyricStates) { track, progress, activity, lyric ->
        if (track == null) null else {
            // 切歌加载期间先标记为未就绪，别误判成已停止导致系统灵动岛/状态栏歌词闪退。
            val ready = progress.trackId == track.id && player.currentMediaItem?.mediaId == track.id
            SystemPlaybackPresentation(
            SystemPlaybackKey(track.id, progress.generation), track.title, track.artist, track.artworkUri,
            track.lyrics, if (ready) progress.positionMs else 0L, progress.sampledAtMs,
            if (ready) player.duration.takeIf { it > 0L } ?: track.durationMs else track.durationMs,
            ready && activity.trackId == track.id && activity.advancing && player.isPlaying,
            readyForPresentation = ready,
            wordLeadMs = track.wordLeadMs,
            // 歌词状态和当前歌曲是两条流，如果还没对齐到同一首歌，先当成加载中。
            lyricState = if (lyric.first == track.id) lyric.second else LyricLoadState.LOADING,
        )
        }
    // 自然过渡交接后歌词停在上一首，等下一首开唱再切；无过渡时原样透传。
    }.holdAutoMixLyrics({ runtime.autoMixHeldLyricPlan() })
}

/** 把上游状态流共享给多个展示端；异常包在 Result 里往下传，避免一个地方出错把共享协程弄崩。 */
internal fun Flow<SystemPlaybackPresentation?>.sharedSystemPresentations(scope: CoroutineScope): Flow<SystemPlaybackPresentation?> =
    map { Result.success(it) }.catch { emit(Result.failure(it)) }
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0L), replay = 1)
        .map { it.getOrThrow() }

/** 计算歌词最终时间线：本地歌词已经校准过时间，不要再重复叠加手动偏移。 */
internal fun systemLyricLinesForPlayback(track: MusicTrack, manualOffsetMs: Long,
    timing: com.musicone.source.sdk.SourceLyricTiming = sourceLyricTiming(track),
    applyManualOffset: Boolean = track.source.supportsOnlineLyricOffset): List<SystemLyricLine> {
    val offset = if (applyManualOffset) manualOffsetMs else 0L
    val lead = timing.lineLeadMs
    val showTranslation = ExperiencePreferences.options.lyricDisplayContent != LyricDisplayContent.ORIGINAL
    return track.lyrics.map { SystemLyricLine(it.timeMs + (if (it.libraryTimeline) 0 else offset) - lead, it.text,
        it.translation.takeIf { showTranslation }, it) }
}

private data class PreparedSystemPlayback(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val durationMs: Long,
    val lyrics: List<SystemLyricLine>,
    val wordLeadMs: Long,
)
