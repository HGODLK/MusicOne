package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** 保留切换前的完整网格，以标题坐标差补偿列表缩短时系统自动夹紧的滚动位置。 */
internal class QqLibrarySegmentMotion {
    var moving by mutableStateOf(false)
    var headerTop = 0f
    var sourceHeaderTop = 0f
    var contentTop = 0f
    var sourceContentTop = 0f
    var viewportTop = 0f
    var direction = 1f
    var ready: CompletableDeferred<Unit>? = null
    var snapshot: ImageBitmap? = null
    var capture: (suspend () -> ImageBitmap)? = null
    val progress = Animatable(1f)
}

@Composable
internal fun rememberLibrarySegmentSwitch(
    selected: Boolean,
    createdCount: Int,
    collectedCount: Int,
    scroll: LazyGridState,
    headerInset: Float,
    onSelected: (Boolean) -> Unit,
): Pair<QqLibrarySegmentMotion, (Boolean) -> Unit> {
    val motion = remember { QqLibrarySegmentMotion() }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(selected)
    val counts by rememberUpdatedState(createdCount to collectedCount)
    val revealInset by rememberUpdatedState(headerInset)
    val select by rememberUpdatedState(onSelected)
    val switch: (Boolean) -> Unit = { target ->
        if (target != current && !motion.moving) {
            motion.direction = if (target) -1f else 1f
            motion.moving = true
            scope.launch {
                try {
                    val (created, collected) = counts
                    val sourceCount = if (current) created else collected
                    val targetCount = if (target) created else collected
                    if (targetCount < sourceCount) {
                        // 短列表切换前先把被顶栏遮住的分区按钮平滑带回可见区域。
                        val distance = (motion.headerTop - motion.viewportTop - revealInset).coerceAtMost(0f)
                        if (distance < -1f) {
                            scroll.animateScrollBy(distance, musicMotion(360))
                            withFrameNanos { }
                        }
                    }
                    motion.sourceHeaderTop = motion.headerTop
                    motion.sourceContentTop = motion.contentTop - motion.viewportTop
                    motion.progress.snapTo(0f)
                    motion.snapshot = motion.capture?.invoke()
                    motion.ready = CompletableDeferred()
                    select(target)
                    // 等新数据进入组合后，接收对应网格的真实绘制回调。
                    withFrameNanos { }
                    motion.ready?.await()
                    motion.progress.animateTo(1f, musicMotion(360))
                } finally {
                    motion.ready = null
                    motion.snapshot = null
                    motion.moving = false
                }
            }
        }
    }
    return motion to switch
}

@Composable
internal fun Modifier.librarySegmentMotion(motion: QqLibrarySegmentMotion, selected: Boolean): Modifier {
    val previous = rememberGraphicsLayer()
    val incoming = rememberGraphicsLayer()
    val fadePaint = remember { Paint() }
    val recordedSelection = remember { arrayOf<Boolean?>(null) }
    val background = MaterialTheme.colorScheme.background
    SideEffect { motion.capture = { previous.toImageBitmap() } }
    return drawWithContent {
        if (!motion.moving || recordedSelection[0] == null || recordedSelection[0] == selected) {
            previous.record { this@drawWithContent.drawContent() }
            recordedSelection[0] = selected
            drawLayer(previous)
        } else {
            incoming.record { this@drawWithContent.drawContent() }
            val p = motion.progress.value
            val delta = motion.sourceHeaderTop - motion.headerTop
            val boundary = (motion.sourceContentTop - delta * p).coerceIn(0f, size.height)
            // 与编辑菜单复用八分之一宽度的横移动效；上下滚动补偿独立保留。
            val travel = size.width / 8f * motion.direction
            drawRect(background)
            // 固定区域仅跟随纵向位置，不让资料、我喜欢和分区按钮横向退场。
            incoming.alpha = 1f
            clipRect(bottom = boundary) {
                translate(top = delta * (1f - p)) { drawLayer(incoming) }
            }
            clipRect(top = boundary) {
                translate(left = -travel * p, top = -delta * p) {
                    motion.snapshot?.let { drawImage(it, alpha = 1f - p) }
                }
                // 分区内包括空态和加载提示，统一交接，不单独突然切换。
                fadePaint.alpha = p
                drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), fadePaint)
                translate(left = travel * (1f - p), top = delta * (1f - p)) {
                    clipRect(top = motion.contentTop - motion.viewportTop) { drawLayer(incoming) }
                }
                drawContext.canvas.restore()
            }
            motion.ready?.complete(Unit)
        }
    }
}
