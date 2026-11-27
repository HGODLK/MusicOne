package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** 切换歌单分区时的过渡动画状态：保留切换前的网格快照，并用标题位置差补偿列表变短引起的滚动回弹。 */
internal class MainSourceLibrarySegmentMotion {
    var moving by mutableStateOf(false)
    var headerTop = 0f
    var sourceHeaderTop = 0f
    var contentTop = 0f
    var viewportTop = 0f
    var direction = 1f
    var ready: CompletableDeferred<Unit>? = null
    var snapshot: ImageBitmap? = null
    var snapshotTop = 0
    var capture: (suspend () -> ImageBitmap)? = null
    var sourceDrawReady by mutableStateOf<CompletableDeferred<Unit>?>(null)
    val progress = Animatable(1f)
    /** 过渡中的合成画面已录下，打断时用它作为下一段的旧画面，保证画面连续。 */
    var compositeRecorded = false
    /** 过渡期间滚回标题已移动的像素：旧画面跟着平移，新画面本身就是实时内容。 */
    var scrollShift = 0f
    /** 本段过渡从哪个分区切出；往回划时目标可能等于更早的分区，只能和它比，不能和上次静止时的分区比。 */
    var sourceSelection by mutableStateOf<Int?>(null)
    /** 打断后、新目标生效前继续画上一段的合成帧，不闪回实时内容。 */
    var holdComposite by mutableStateOf(false)
    fun transitioning(selected: Int): Boolean = moving && (holdComposite || selected != sourceSelection)
    internal var job: kotlinx.coroutines.Job? = null
    internal var generation = 0L

    suspend fun awaitSourceDraw() {
        val completion = CompletableDeferred<Unit>()
        sourceDrawReady = completion
        try {
            completion.await()
        } finally {
            sourceDrawReady = null
        }
    }
}

@Composable
internal fun rememberLibrarySegmentSwitch(
    selected: Int,
    scroll: LazyGridState,
    headerInset: Float,
    onSelected: (Int) -> Unit,
): Pair<MainSourceLibrarySegmentMotion, (Int) -> Unit> {
    val motion = remember { MainSourceLibrarySegmentMotion() }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(selected)
    val revealInset by rememberUpdatedState(headerInset)
    val select by rememberUpdatedState(onSelected)
    val switch: (Int) -> Unit = { target ->
        if (target != current) {
            // 过渡中再次切换直接打断：以当前合成帧为旧画面接着走，不等上一段播完。
            val interrupting = motion.moving
            val from = current
            motion.direction = if (target < from) -1f else 1f
            if (interrupting) motion.holdComposite = true else motion.sourceSelection = from
            motion.moving = true
            motion.job?.cancel()
            val epoch = ++motion.generation
            motion.job = scope.launch {
                try {
                    if (!interrupting) motion.awaitSourceDraw()
                    // 先截旧画面再重置进度：打断时截的是上一段停住那一帧的合成画面。
                    motion.snapshot = motion.capture?.invoke()
                    motion.sourceHeaderTop = motion.headerTop
                    motion.scrollShift = 0f
                    motion.progress.snapTo(0f)
                    motion.ready = CompletableDeferred()
                    motion.sourceSelection = from
                    motion.holdComposite = false
                    select(target)
                    // 等新数据进入组合后，接收对应网格的真实绘制回调。
                    withFrameNanos { }
                    motion.ready?.await()
                    // 标题滚出屏幕时，滚回标题与横移淡变同时进行，不再先滚完再切；
                    // 标题坐标差由绘制层按进度补偿，滚动途中画面保持连续。
                    val distance = (motion.headerTop - motion.viewportTop - revealInset).coerceAtMost(0f)
                    if (distance < -1f) launch {
                        var last = 0f
                        Animatable(0f).animateTo(distance, musicMotion(360)) {
                            // 逐帧滚动并记下实际位移：标题坐标差只补偿列表变短的夹紧，不把滚动算进去。
                            val moved = -scroll.dispatchRawDelta(value - last)
                            last = value
                            motion.scrollShift += moved
                            motion.sourceHeaderTop += moved
                        }
                    }
                    motion.progress.animateTo(1f, musicMotion(360))
                } finally {
                    if (motion.generation == epoch) {
                        motion.sourceDrawReady = null
                        motion.ready = null
                        motion.snapshot = null
                        motion.moving = false
                        motion.holdComposite = false
                        motion.sourceSelection = null
                        motion.job = null
                    }
                }
            }
        }
    }
    return motion to switch
}

@Composable
internal fun Modifier.librarySegmentMotion(motion: MainSourceLibrarySegmentMotion, selected: Int): Modifier {
    val previous = rememberGraphicsLayer()
    val incoming = rememberGraphicsLayer()
    val captureLayer = rememberGraphicsLayer()
    val composite = rememberGraphicsLayer()
    val fadePaint = remember { Paint() }
    val background = MaterialTheme.colorScheme.background

    return drawWithContent {
        // 旧资料区从不参与退场，只截取分区内容，文字与封面仍保持原像素。
        motion.capture = {
            // 打断时取过渡中的合成帧，否则取切换前的完整网格。
            val source = if (motion.holdComposite && motion.compositeRecorded) composite else previous
            val top = librarySegmentSnapshotTop(motion.contentTop, motion.viewportTop, source.size.height)
            motion.snapshotTop = top
            captureLayer.record(size = IntSize(source.size.width, (source.size.height - top).coerceAtLeast(1))) {
                translate(top = -top.toFloat()) { drawLayer(source) }
            }
            captureLayer.toImageBitmap()
        }
        if (!motion.transitioning(selected)) {
            previous.record { this@drawWithContent.drawContent() }
            motion.compositeRecorded = false
            drawLayer(previous)
            motion.sourceDrawReady?.complete(Unit)
        } else {
            incoming.record { this@drawWithContent.drawContent() }
            val p = motion.progress.value
            val delta = motion.sourceHeaderTop - motion.headerTop
            val verticalShift = delta * (1f - p)
            // 裁剪线直接跟随新画面的按钮底部，避免固定区域与歌单区域之间出现缺口。
            val boundary = (motion.contentTop - motion.viewportTop + verticalShift).coerceIn(0f, size.height)
            // 与编辑菜单复用八分之一宽度的横移动效；上下滚动补偿独立保留。
            val travel = size.width / 8f * motion.direction
            composite.record {
                drawRect(background)
                // 固定区域仅跟随纵向位置，不让资料、我喜欢和分区按钮横向退场。
                incoming.alpha = 1f
                clipRect(bottom = boundary) {
                    translate(top = verticalShift) { drawLayer(incoming) }
                }
                clipRect(top = boundary) {
                    translate(left = -travel * p, top = -delta * p + motion.scrollShift) {
                        motion.snapshot?.let { image ->
                            translate(top = motion.snapshotTop.toFloat()) { drawImage(image, alpha = 1f - p) }
                        }
                    }
                    // 分区内包括空态和加载提示，统一交接，不单独突然切换。
                    fadePaint.alpha = p
                    drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), fadePaint)
                    translate(left = travel * (1f - p), top = verticalShift) {
                        clipRect(top = motion.contentTop - motion.viewportTop) { drawLayer(incoming) }
                    }
                    drawContext.canvas.restore()
                }
            }
            motion.compositeRecorded = true
            drawLayer(composite)
            motion.ready?.complete(Unit)
        }
    }
}

/** 采样起点向下取整，避免分区交界处被裁掉 1px 出现白线。 */
internal fun librarySegmentSnapshotTop(contentTop: Float, viewportTop: Float, height: Int): Int =
    floor(contentTop - viewportTop).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
