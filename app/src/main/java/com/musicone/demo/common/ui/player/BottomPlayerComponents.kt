package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

@Composable
internal fun MiniPlayer(
    track: MusicTrack,
    isPlaying: Boolean,
    isFavorite: Boolean,
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    immersiveVisual: NeteaseProfileVisual? = null,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    previewSwipe: (Boolean) -> MusicTrack?,
    commitSwipe: (MusicTrack, Boolean) -> Unit,
) {
    val motion = LocalPlayerMotion.current
    val qqFeedback = track.source == MusicSource.QQ
    val swipe = rememberMiniPlayerSwipe(track)
    androidx.compose.runtime.CompositionLocalProvider(LocalMiniPlayerImmersiveVisual provides immersiveVisual) {
    MiniPlayerInkProvider(backdropLayer, backdropBounds) { ink ->
    MiniPlayerSurface(backdropLayer, backdropBounds, immersiveVisual, Modifier
        .miniPlayerSwipe(swipe, track, motion?.mounted != true, previewSwipe, commitSwipe)
        .onGloballyPositioned { ink.bounds = it.boundsInRoot() }
        .motionAnchor(motion, "surface", false)) {
        MiniPlayerInformation(track, isFavorite, swipe, onOpen, onFavorite)
        PlaybackControlButton(
            qqFeedback = qqFeedback,
            onClick = onToggle,
            modifier = Modifier.motionAnchor(motion, "play", false),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides miniPlayerInkColor("play")) {
                PlaybackStateIcon(isPlaying, qqFeedback, Modifier.size(21.dp).miniPlayerInkRegion("play"))
            }
        }
        PlaybackControlButton(
            qqFeedback = qqFeedback,
            onClick = onNext,
            horizontalMotion = 5.dp,
            modifier = Modifier
                .motionAnchor(motion, "miniNext", false, hideDuringMotion = false)
                .graphicsLayer { alpha = miniPlayerAccessoryAlpha(motion?.value ?: 0f) },
        ) { Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = miniPlayerInkColor("next"),
            modifier = Modifier.size(21.dp).miniPlayerInkRegion("next")) }
    }
    }
    }
}

@Composable
private fun MiniPlayerSurface(
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    immersiveVisual: NeteaseProfileVisual?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    MusicOneBackdropGlass(
        backdropLayer = backdropLayer,
        backdropBounds = backdropBounds,
        modifier = Modifier.fillMaxWidth().widthIn(max = 1_280.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp).then(modifier),
        shape = RoundedCornerShape(19.dp),
        blurRadius = 18.dp,
        containerColor = immersiveVisual?.panelColor
            ?: if (dark) Color(0xFF242526).copy(alpha = .82f) else Color.White.copy(alpha = .28f),
        fallbackColor = immersiveVisual?.panelColor
            ?: if (dark) Color(0xFF242526) else Color(0xFFF6F7F8),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            immersiveVisual?.panelBorder
                ?: if (dark) Color.White.copy(alpha = .16f) else Color(0xFFE0E2E6),
        ),
        backgroundSnapshot = LocalPlayerSurfaceTexture.current,
    ) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    }
}
