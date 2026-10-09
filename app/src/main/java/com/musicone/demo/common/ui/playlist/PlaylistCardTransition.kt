package com.musicone.demo

import android.graphics.Bitmap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaylistCardTransition(private val scope: CoroutineScope) {
    val artworkSync = PlaylistArtworkSynchronization()
    var activeArtworkLayers by mutableStateOf<List<ArtworkBlendSnapshot>?>(null)
        private set
    fun artworkLocked(key: String) = activeSourceKey == key || artworkSync.held(key)
    fun preparePlayback(key: String) = artworkSync.holdPlayback(key)
    suspend fun releasePlaybackAfterReturn() = artworkSync.returned()
    var activeSourceKey by mutableStateOf<String?>(null)
        private set
    private val opacity = Animatable(1f)
    var busy by mutableStateOf(false)
        private set
    var activeArtwork by mutableStateOf<Bitmap?>(null)
        private set

    fun alphaFor(sourceKey: String) = if (activeSourceKey == sourceKey) opacity.value else 1f

    fun open(sourceKey: String, artwork: Bitmap?, reveal: suspend () -> Unit, navigate: () -> Unit) {
        if (busy || activeSourceKey != null) return
        busy = true
        activeSourceKey = sourceKey
        activeArtwork = artwork
        activeArtworkLayers = artworkSync.snapshot(sourceKey)
        scope.launch {
            try {
                reveal()
                opacity.animateTo(0f, musicMotion(120))
                navigate()
            } finally {
                busy = false
                // 滚动被取消时恢复卡片，避免留下透明信息。
                if (opacity.value != 0f) restore()
            }
        }
    }

    fun restore() {
        scope.launch {
            // 飞回就位后缓缓恢复信息，不沿用位移动画前段较快的曲线。
            opacity.animateTo(1f, if (ExperiencePreferences.options.reduceMotion) snap() else
                tween(300, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)))
            activeSourceKey = null
            activeArtwork = null
            activeArtworkLayers = null
        }
    }

}

internal data class PlaylistCardViewport(
    val height: Dp = 0.dp,
    val reveal: suspend (() -> androidx.compose.ui.geometry.Rect) -> Unit = {},
)

internal fun playlistScrollDistance(card: androidx.compose.ui.geometry.Rect, viewport: androidx.compose.ui.geometry.Rect): Float = when {
    card.top < viewport.top -> card.top - viewport.top
    card.bottom > viewport.bottom -> card.bottom - viewport.bottom
    else -> 0f
}
internal val LocalPlaylistCardTransition = staticCompositionLocalOf<PlaylistCardTransition?> { null }
internal val LocalPlaylistCardViewport = compositionLocalOf { PlaylistCardViewport() }
