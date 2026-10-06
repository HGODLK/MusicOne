package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale

internal val LocalPlayerSurfaceTexture = staticCompositionLocalOf<GraphicsLayer?> { null }
internal val LocalPlayerAtmosphereTexture = staticCompositionLocalOf<GraphicsLayer?> { null }
internal val LocalPlayerBackdropTexture = staticCompositionLocalOf<GraphicsLayer?> { null }
internal val LocalPlayerBackdropBounds = staticCompositionLocalOf { androidx.compose.ui.geometry.Rect.Zero }

internal fun playerMaterialProgress(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

@Composable
internal fun playerForegroundColor(): Color =
    LocalNeteaseGlobalVisual.current?.foreground ?: Color.White

@Composable
internal fun PlayerAtmosphere(track: MusicTrack, contentActivated: Boolean) {
    val texture = LocalPlayerAtmosphereTexture.current
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < .3f
    val customVisual = LocalNeteaseGlobalVisual.current
    Box(Modifier.fillMaxSize().drawWithContent {
        if (texture == null) drawContent() else {
            texture.record { this@drawWithContent.drawContent() }
            drawLayer(texture)
        }
    }) {
        if (customVisual != null) {
            NeteaseProfileBackdrop(customVisual, Modifier.fillMaxSize())
        } else {
            val frame by rememberPlayerArtwork(track)
            // 首帧与稳定态共用同一个色场绘制器，不再叠加第二层入场背景。
            FlowingArtworkAtmosphere(frame)
            Box(Modifier.fillMaxSize().drawWithCache {
                // 静态阴影合并到同一渐变，画刷仅随尺寸重建。
                val shade = Brush.verticalGradient(
                    if (dark) listOf(Color.Black.copy(alpha = .5f), Color.Black.copy(alpha = .7f))
                    else listOf(Color.Black.copy(alpha = .3f), Color.Black.copy(alpha = .51f)),
                )
                onDrawBehind { drawRect(shade) }
            })
        }
    }
}
