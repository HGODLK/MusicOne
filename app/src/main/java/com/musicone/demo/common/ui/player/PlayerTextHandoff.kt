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
import androidx.compose.foundation.layout.fillMaxSize

internal class PlayerTextHandoff(
    private val title: GraphicsLayer,
    private val subtitle: GraphicsLayer,
) {
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
internal fun Modifier.playerTextHandoffCapture(motion: PageMotion, key: String): Modifier = composed {
    val layer = LocalPlayerTextHandoff.current?.layer(key) ?: return@composed this
    drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        val flying = motion.moving &&
            motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null
        if (!flying) drawLayer(layer)
    }
}

@Composable
internal fun PlayerTextHandoffSnapshot(key: String) {
    val layer = LocalPlayerTextHandoff.current?.layer(key) ?: return
    Canvas(Modifier.fillMaxSize()) {
        if (layer.size.width <= 0 || layer.size.height <= 0) return@Canvas
        withTransform({
            scale(
                scaleX = size.width / layer.size.width,
                scaleY = size.height / layer.size.height,
                pivot = Offset.Zero,
            )
        }) {
            drawLayer(layer)
        }
    }
}
