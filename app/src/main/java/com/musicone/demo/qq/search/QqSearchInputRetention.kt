package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 输入框只有一个实例；覆盖详情时把现有文字图层交回来源页，让详情自然遮住它。 */
internal fun Modifier.retainQqSearchInput(motion: QqSearchMotion, full: Boolean,
    obscured: Boolean, visibility: () -> Float): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val fullSearch by rememberUpdatedState(full)
    val covered by rememberUpdatedState(obscured)
    val currentVisibility by rememberUpdatedState(visibility)
    SideEffect {
        motion.inputLayer = layer
        motion.inputVisibility = { currentVisibility() }
    }
    DisposableEffect(motion, layer) {
        onDispose {
            if (motion.inputLayer === layer) {
                motion.inputLayer = null
                motion.inputBounds = Rect.Zero
                motion.inputVisibility = { 1f }
            }
        }
    }
    onGloballyPositioned { motion.inputBounds = it.boundsInRoot() }.drawWithContent {
        if (!covered) layer.record { this@drawWithContent.drawContent() }
        if (!fullSearch || (!covered && currentVisibility() == 1f)) drawLayer(layer)
    }
}

/** 仅绘制输入行，不采样玻璃，避免与根背景图层形成自引用。 */
@Composable
internal fun RetainedQqSearchInput(motion: QqSearchMotion, full: Boolean, obscured: Boolean) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
        val layer = motion.inputLayer
        val input = motion.inputBounds
        // 返回时一直保留来源文字，等上层完全恢复后在同一帧交回，避免先消失再淡入。
        if (full && (obscured || motion.inputVisibility() < 1f) && motion.resultsReady &&
            layer != null && !input.isEmpty && !bounds.isEmpty) {
            translate(input.left - bounds.left, input.top - bounds.top) { drawLayer(layer) }
        }
    }
}
