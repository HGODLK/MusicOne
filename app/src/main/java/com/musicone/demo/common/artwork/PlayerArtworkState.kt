package com.musicone.demo

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

// 仅使用封面属性作为切换标识，歌词、播放地址和音质更新不会重启渐变。
internal data class ArtworkIdentity(val url: String?, val start: Long, val end: Long, val mark: String)

internal fun MusicTrack.artworkIdentity() = ArtworkIdentity(artworkUrl, artworkStart, artworkEnd, artworkMark)

internal data class PlayerArtworkFrame(val identity: ArtworkIdentity, val bitmap: Bitmap?)

@Composable
internal fun rememberPlayerArtwork(track: MusicTrack): State<PlayerArtworkFrame> {
    val identity = track.artworkIdentity()
    val initial = remember { PlayerArtworkFrame(identity, ArtworkRepository.peek(identity.url)) }
    return produceState(initial, identity) {
        // 每次实际切换立即准备封面，旧封面在准备期间保持可见。
        val bitmap = ArtworkRepository.peek(identity.url) ?: ArtworkRepository.load(identity.url)
        ArtworkColorFieldRepository.prepare(identity, bitmap)
        if (bitmap == null && value.bitmap != null && track.source == MusicSource.QQ && identity.url == null) {
            // QQ 缺失 albumMid 时详情补全仍在进行；保留旧封面，元数据更新会取消本次等待。
            delay(12_000)
        }
        // 加载期间保留上一张真实封面；目标确认无封面后才使用占位图。
        coroutineContext.ensureActive()
        value = PlayerArtworkFrame(identity, bitmap)
    }
}

internal fun adjacentArtworkUrls(current: MusicTrack?, queue: List<MusicTrack>): List<String> {
    if (current == null) return emptyList()
    val index = queue.indexOfFirst { it.id == current.id && it.source == current.source }
    val neighbors = if (index < 0) emptyList() else listOf(
        queue[(index + 1) % queue.size], queue[(index - 1 + queue.size) % queue.size],
    )
    return (listOf(current) + neighbors).mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }.distinct()
}

internal fun CoroutineScope.prefetchPlayerArtwork(state: StateFlow<MusicOneUiState>) = launch {
    state.map { adjacentArtworkTracks(it.currentTrack, it.queue) }.distinctUntilChanged().collectLatest { tracks ->
        coroutineScope {
            tracks.firstOrNull()?.let { track -> launch {
                val bitmap = ArtworkRepository.load(track.artworkUrl)
                ArtworkColorFieldRepository.prepare(track.artworkIdentity(), bitmap)
            } }
            // 当前目标优先；新进入窗口的邻曲等交叉渐变结束后再解码，避免抢占切歌帧预算。
            delay(PLAYER_ARTWORK_NEIGHBOR_PREFETCH_DELAY_MS)
            tracks.drop(1).forEach { track -> launch {
                val bitmap = ArtworkRepository.load(track.artworkUrl)
                ArtworkColorFieldRepository.prepare(track.artworkIdentity(), bitmap)
            } }
        }
    }
}

private fun adjacentArtworkTracks(current: MusicTrack?, queue: List<MusicTrack>): List<MusicTrack> {
    if (current == null) return emptyList()
    val index = queue.indexOfFirst { it.id == current.id && it.source == current.source }
    val neighbors = if (index < 0) emptyList() else listOf(
        queue[(index + 1) % queue.size], queue[(index - 1 + queue.size) % queue.size],
    )
    return (listOf(current) + neighbors).distinctBy { it.artworkUrl }
}

private const val PLAYER_ARTWORK_NEIGHBOR_PREFETCH_DELAY_MS = 1_000L
