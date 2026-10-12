package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.fillMaxSize

internal class PlayerTextHandoff(
    private val title: GraphicsLayer,
    private val subtitle: GraphicsLayer,
) {
    val frames = androidx.compose.runtime.mutableStateMapOf<String, PlayerTextFrame>()
    val clipWidths = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    fun layer(key: String): GraphicsLayer? = when (key) {
        "title" -> title
        "subtitle" -> subtitle
        else -> null
    }
}

@Composable
internal fun rememberPlayerTextHandoff(): PlayerTextHandoff {
    val title = rememberGraphicsLayer()
    val subtitle = rememberGraphicsLayer()
    return androidx.compose.runtime.remember(title, subtitle) { PlayerTextHandoff(title, subtitle) }
}

internal val LocalPlayerTextHandoff = staticCompositionLocalOf<PlayerTextHandoff?> { null }

/** 持续录制正在滚动的播放页文字，转场时由顶层飞行控件直接绘制同一帧。 */
internal fun Modifier.playerTextHandoffCapture(motion: PageMotion, key: String, track: MusicTrack): Modifier = composed {
    val handoff = LocalPlayerTextHandoff.current ?: return@composed this
    val layer = handoff.layer(key) ?: return@composed this
    val identity = track.playerTextIdentity()
    drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        val frame = PlayerTextFrame(identity, layer.size)
        if (handoff.frames[key] != frame) handoff.frames[key] = frame
        val flying = motion.moving &&
            motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null
        if (!flying) {
            val width = handoff.clipWidths[key]
            if (width == null) drawLayer(layer) else clipRect(right = width) { drawLayer(layer) }
        }
    }
}

@Composable
internal fun PlayerTextHandoffSnapshot(key: String) {
    val layer = LocalPlayerTextHandoff.current?.layer(key) ?: return
    Canvas(Modifier.fillMaxSize()) {
        if (layer.size.width <= 0 || layer.size.height <= 0) return@Canvas
        clipRect {
            withTransform({
                scale(
                    scaleX = size.height / layer.size.height,
                    scaleY = size.height / layer.size.height,
                    pivot = Offset.Zero,
                )
            }) { drawLayer(layer) }
        }
    }
}
