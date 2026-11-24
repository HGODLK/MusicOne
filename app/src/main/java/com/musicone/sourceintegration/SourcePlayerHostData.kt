package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.*
import com.musicone.source.sdk.*

/** 包只看接下来一段，避免长队列每次变动都整表转换。 */
internal const val SOURCE_PLAYER_QUEUE_WINDOW = 50

/** 宿主队列本身就是实际播放顺序（随机时已打乱）；当前歌曲不在队列时没有后续。 */
internal fun sourcePlayerQueueSnapshot(state: MusicOneUiState): SourcePlayerQueueSnapshot {
    val queue = state.queue
    val currentId = state.currentTrack?.id
    val index = if (currentId == null) -1 else queue.indexOfFirst { it.id == currentId }
    val upcoming = if (index < 0) emptyList()
        else queue.subList(index + 1, minOf(queue.size, index + 1 + SOURCE_PLAYER_QUEUE_WINDOW)).map { it.toSourceTrack() }
    return SourcePlayerQueueSnapshot(index, queue.size, upcoming)
}

internal fun LyricLoadState.toSourcePlayerLyricsState(): SourcePlayerLyricsState = when (this) {
    LyricLoadState.LOADING -> SourcePlayerLyricsState.LOADING
    LyricLoadState.READY -> SourcePlayerLyricsState.READY
    LyricLoadState.RETRYABLE -> SourcePlayerLyricsState.RETRYABLE
    LyricLoadState.UNAVAILABLE -> SourcePlayerLyricsState.UNAVAILABLE
}

/** 只在 READY 时交出正文，与宿主歌词窗口一致：加载中或失败都不能沿用上一首的行。 */
internal fun sourcePlayerLyrics(track: MusicTrack?, state: LyricLoadState,
    timing: (MusicTrack) -> SourceLyricTiming = ::sourceLyricTiming): SourcePlayerLyrics {
    if (track == null) return SourcePlayerLyrics("", SourcePlayerLyricsState.UNAVAILABLE, emptyList())
    val lines = if (state == LyricLoadState.READY) track.lyrics.map { line ->
        SourcePlayerLyricLine(line.timeMs, line.text, line.translation,
            line.words.map { SourcePlayerLyricWord(it.startMs, it.endMs, it.text) }, line.transliteration)
    } else emptyList()
    val lead = timing(track)
    return SourcePlayerLyrics(track.id, state.toSourcePlayerLyricsState(), lines, lead.lineLeadMs, lead.wordLeadMs)
}

internal fun sourcePlayerAudioInfo(audio: PlaybackAudioSnapshot?, output: String?): SourcePlayerAudioInfo? =
    audio?.let { SourcePlayerAudioInfo(it.codec, it.sampleRate, it.bitDepth, it.bitrate, it.summary, output) }

/** 解码规格与输出链路分别来自播放器和输出状态，合成一份供包读取。 */
@Composable
internal fun rememberSourcePlayerAudioInfo(): State<SourcePlayerAudioInfo?> {
    val audio by PlaybackAudioInfo.snapshot.collectAsState()
    val system by PlaybackOutputStatus.system.collectAsState()
    val lg by PlaybackOutputStatus.lg.collectAsState()
    val usb by UsbAudioOutputStatus.current.collectAsState()
    val output = remember(system, lg, usb) {
        playbackOutputDescription(system, lg, usb).takeIf { it != "等待播放" }
    }
    return rememberUpdatedState(remember(audio, output) { sourcePlayerAudioInfo(audio, output) })
}

/** 与原生播放页同一套封面色场和压暗；包页面在独立组合中，播放可见性按页面可交互提供。 */
@Composable
internal fun SourcePlayerBackdropContent(track: MusicTrack, visible: Boolean,
    direction: () -> TrackTransitionDirection, modifier: Modifier) {
    Box(modifier) {
        CompositionLocalProvider(LocalPlayerVisible provides visible) {
            PlayerAtmosphere(track, contentActivated = true, direction = direction)
        }
    }
}

/**
 * 迷你条元素的副本，样式、字号与墨色取迷你条在开合起点的真实值，与原生飞行控件的源端一致。
 * 封面两端是同一张图，不需要副本。
 */
@Composable
internal fun SourcePlayerBarCopy(element: SourcePlayerSharedElement, track: MusicTrack, playing: Boolean,
    textSizeSp: Float) {
    val handoff = LocalPlayerControlHandoff.current
    when (element) {
        SourcePlayerSharedElement.TITLE -> MiniPlayerText(playerPrimaryTitle(track.title),
            handoff?.sourceInk("title", Color.Black) ?: Color.Black,
            fontSize = (textSizeSp.takeIf { it > 0f } ?: 14f).sp)
        SourcePlayerSharedElement.ARTIST -> MiniPlayerText(playerPrimaryArtists(track.artists),
            handoff?.sourceInk("artist", Color.Black) ?: Color.Black, artist = true,
            fontSize = (textSizeSp.takeIf { it > 0f } ?: 12f).sp)
        SourcePlayerSharedElement.PLAY_PAUSE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(if (playing) PlayerControlIcons.Pause else PlayerControlIcons.Play, null,
                tint = handoff?.sourceInk("play", Color.Black) ?: Color.Black,
                modifier = Modifier.size(MINI_PLAYER_PLAY_ICON_DP.dp))
        }
        SourcePlayerSharedElement.ARTWORK -> Unit
    }
}

/** 迷你条播放键图标边长，锚点 markSize 与副本共用。 */
internal const val MINI_PLAYER_PLAY_ICON_DP = 21f
