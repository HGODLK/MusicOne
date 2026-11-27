package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** 输入框毛玻璃单独采样聊天列表和未裁切的飞行封面，玻璃自己不进采样层。 */
internal class AiComposerBackdrop(val layer: GraphicsLayer, val artwork: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
    var artworkBounds by mutableStateOf(Rect.Zero)
}

internal val LocalAiComposerBackdrop = staticCompositionLocalOf<AiComposerBackdrop?> { null }

/** 把聊天层和封面层合成到输入框的背景采样层里，封面动画只触发重绘，不触发聊天布局重算。 */
internal fun Modifier.aiChatBackdrop(chat: GraphicsLayer, bounds: () -> Rect, sampling: AiComposerBackdrop?): Modifier =
    drawWithContent {
        chat.record { this@drawWithContent.drawContent() }
        sampling?.let { source ->
            source.layer.record {
                drawLayer(chat)
                val artworkBounds = source.artworkBounds
                if (!artworkBounds.isEmpty) {
                    val origin = artworkBounds.topLeft - bounds().topLeft
                    translate(origin.x, origin.y) { drawLayer(source.artwork) }
                }
            }
        }
        drawLayer(chat)
    }

/** 先把飞行封面完整录进采样层给毛玻璃用，再正常画出来，避免清晰封面盖住输入框。 */
internal fun Modifier.aiComposerArtworkBackdrop(sampling: AiComposerBackdrop?): Modifier {
    if (sampling == null) return this
    val path = Path()
    return onGloballyPositioned { sampling.artworkBounds = it.boundsInRoot() }.drawWithContent {
        sampling.artwork.record { this@drawWithContent.drawContent() }
        val blocked = sampling.bounds.translate(-sampling.artworkBounds.left, -sampling.artworkBounds.top)
        if (blocked.isEmpty) drawLayer(sampling.artwork)
        else {
            path.reset()
            path.addRoundRect(RoundRect(blocked, CornerRadius(28.dp.toPx())))
            clipPath(path, ClipOp.Difference) { drawLayer(sampling.artwork) }
        }
    }
}
