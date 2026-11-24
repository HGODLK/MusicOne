package com.musicone

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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun MiniPlayer(
    track: MusicTrack,
    isPlaying: Boolean,
    isFavorite: Boolean,
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    immersiveVisual: SourceBackdropVisual? = null,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    previewSwipe: (Boolean) -> MusicTrack?,
    commitSwipe: (MusicTrack, Boolean) -> Unit,
    openGesture: Modifier = Modifier,
) {
    val motion = LocalPlayerMotion.current
    val barMotionEnabled = LocalSourcePlayerBarMotionEnabled.current
    val swipe = rememberMiniPlayerSwipe(track)
    val accessoryReveal = LocalMiniAccessoryReveal.current
    androidx.compose.runtime.CompositionLocalProvider(LocalMiniPlayerImmersiveVisual provides immersiveVisual) {
    MiniPlayerInkProvider(backdropLayer, backdropBounds) { ink ->
    MiniPlayerSurface(backdropLayer, backdropBounds, immersiveVisual, Modifier
        .then(openGesture)
        .miniPlayerSwipe(swipe, track, motion?.mounted != true, previewSwipe, commitSwipe)
        .onGloballyPositioned { ink.bounds = it.boundsInRoot() }
        .motionAnchor(motion, "surface", false)) {
        MiniPlayerInformation(track, isFavorite, swipe, onOpen, onFavorite)
        val playButton: @Composable () -> Unit = {
            PlaybackControlButton(
                onClick = onToggle,
                modifier = Modifier.motionAnchor(motion, "play", false,
                    markSize = com.musicone.sourceintegration.MINI_PLAYER_PLAY_ICON_DP),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides miniPlayerInkColor("play")) {
                    PlaybackStateIcon(isPlaying, Modifier.size(com.musicone.sourceintegration.MINI_PLAYER_PLAY_ICON_DP.dp).miniPlayerInkRegion("play"))
                }
            }
        }
        val nextButton: @Composable () -> Unit = {
            PlaybackControlButton(
                onClick = onNext,
                horizontalMotion = 5.dp,
                modifier = Modifier
                    .miniAccessoryReveal(accessoryReveal)
                    .motionAnchor(motion, "miniNext", false, hideDuringMotion = false)
                    .graphicsLayer { alpha = if (barMotionEnabled) miniPlayerAccessoryAlpha(motion?.value ?: 0f) else 1f },
            ) { Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = miniPlayerInkColor("next"),
                modifier = Modifier.size(21.dp).miniPlayerInkRegion("next")) }
        }
        // 两种底栏的按钮顺序一致：收藏、暂停、下一首。
        playButton()
        nextButton()
    }
    }
    }
}

@Composable
private fun MiniPlayerSurface(
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    immersiveVisual: SourceBackdropVisual?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val borderColor = immersiveVisual?.panelBorder
        ?: if (dark) Color.White.copy(alpha = .16f) else Color(0xFFE0E2E6)
    val handoff = LocalPlayerControlHandoff.current
    val barStyle = LocalBottomBarStyle.current
    val direction = LocalLayoutDirection.current
    SideEffect { handoff?.observeInk("surfaceBorder", borderColor) }
    androidx.compose.runtime.CompositionLocalProvider(LocalMiniPlayerGlassSampling provides
        rememberMiniPlayerGlassSampling(backdropLayer)) {
    MusicOneBackdropGlass(
        backdropLayer = backdropLayer,
        backdropBounds = backdropBounds,
        modifier = Modifier.fillMaxWidth().widthIn(max = BottomBarMaxWidth)
            .padding(horizontal = 12.dp, vertical = MiniPlayerLayoutMetrics.outerPadding).then(modifier),
        shape = RoundedCornerShape(barStyle.surfaceCorner),
        blurRadius = 18.dp,
        containerColor = immersiveVisual?.panelColor
            ?: if (dark) Color(0xFF242526).copy(alpha = .82f) else Color.White.copy(alpha = barStyle.lightTint),
        fallbackColor = immersiveVisual?.panelColor
            ?: if (dark) Color(0xFF242526) else Color(0xFFF6F7F8),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            borderColor,
        ),
        backgroundSnapshot = LocalPlayerSurfaceTexture.current,
    ) {
        // 反转时只镜像这一行的排列顺序（封面贴向另一侧），行内文字和图标仍按原方向排版。
        androidx.compose.runtime.CompositionLocalProvider(
            LocalLayoutDirection provides if (barStyle.mirrored) LayoutDirection.Rtl else direction,
        ) {
            Row(Modifier.padding(MiniPlayerLayoutMetrics.innerPadding), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides direction) { content() }
            }
        }
    }
    }
    }
}
