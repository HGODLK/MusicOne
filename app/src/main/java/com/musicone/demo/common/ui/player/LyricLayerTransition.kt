package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.math.abs

internal enum class LyricLayerTransitionState {
    NORMAL,
    PREPARING,
    TRANSITIONING,
}

internal class LyricWindowPane(
    val id: Long,
    val initialLine: Int,
    val listState: LazyListState,
    val playbackStep: LyricPlaybackStepMotion,
    var isFrozen: Boolean = false,
    val requestId: Long = 0L,
) {
    var currentLine by mutableIntStateOf(initialLine)
    val ready = CompletableDeferred<Unit>()
    var drawnFirstFrame = false
}

/**
 * 同曲内图层定位控制器：
 * 负责管理来源窗口冻结、目标窗口屏外准备、布局/绘制确认就绪、图层滑动交接及窗口释放。
 */
internal class LyricLayerTransitionController(
    val trackId: String,
    initialLine: Int,
) {
    var state by mutableStateOf(LyricLayerTransitionState.NORMAL)
        private set

    var currentPane by mutableStateOf(
        LyricWindowPane(
            id = 1L,
            initialLine = initialLine,
            listState = LazyListState(firstVisibleItemIndex = initialLine),
            playbackStep = LyricPlaybackStepMotion(initialLine),
        ),
    )
        private set

    var outgoingPane by mutableStateOf<LyricWindowPane?>(null)
        private set

    var latestPlaybackLine by mutableIntStateOf(initialLine)
        private set

    val seekOffset = Animatable(0f)
    var seekTravel by mutableFloatStateOf(0f)
        private set

    private var paneSequence = 1L
    private var requestId = 0L
    private var animationJob: Job? = null

    var previewActive by mutableStateOf(false)
        private set
    var previewPreparing by mutableStateOf(false)
        private set
    var previewPane by mutableStateOf<LyricWindowPane?>(null)
        private set
    private var previewTarget: Pair<Int, CompletableDeferred<Unit>>? = null

    suspend fun previewTo(
        line: Int,
        position: suspend (LyricWindowPane, Int) -> Unit = { pane, index -> pane.listState.scrollToItem(index) },
    ) {
        if (!previewActive) {
            previewActive = true
            previewPreparing = state != LyricLayerTransitionState.NORMAL
            // 先使旧请求失效，旧任务的 finally 不得清除接管中的来源画面。
            requestId++
            val interrupted = animationJob
            animationJob = null
            if (previewPreparing) {
                previewPane = LyricWindowPane(++paneSequence, line, LazyListState(firstVisibleItemIndex = line),
                    LyricPlaybackStepMotion(line), requestId = requestId)
            }
            interrupted?.cancelAndJoin()
        }
        val pane = previewPane ?: currentPane
        val ready = CompletableDeferred<Unit>()
        previewTarget = line to ready
        position(pane, line)
        pane.playbackStep.consume(line, enabled = false)
        // 隐藏目标直接准备完整焦点，已显示的预览仍保留原行内接续效果。
        pane.playbackStep.reset(if (previewPreparing) line else null)
        pane.currentLine = line
        if (previewPreparing) ready.await()
        seekOffset.snapTo(0f)
        Snapshot.withMutableSnapshot {
            currentPane = pane
            state = LyricLayerTransitionState.NORMAL
            outgoingPane = null
            seekTravel = 0f
            pane.isFrozen = false
            previewPreparing = false
            previewPane = null
        }
        previewTarget = null
    }

    fun previewDrawn(pane: LyricWindowPane, drawnLine: Int?, offset: Int?) {
        val target = previewTarget ?: return
        if (pane === (previewPane ?: currentPane) && pane.currentLine == target.first &&
            drawnLine == target.first && offset == 0
        ) target.second.complete(Unit)
    }

    suspend fun finishPreview() {
        if (!previewActive) return
        seekOffset.snapTo(0f)
        Snapshot.withMutableSnapshot {
            previewActive = false
            previewPreparing = false
            state = LyricLayerTransitionState.NORMAL
            outgoingPane = null
            seekTravel = 0f
            currentPane.isFrozen = false
            previewPane = null
        }
        previewTarget = null
    }

    fun updatePlaybackLine(line: Int, present: Boolean = true) {
        latestPlaybackLine = line
        if (present && state == LyricLayerTransitionState.NORMAL && !previewActive) {
            currentPane.currentLine = line
        }
    }

    fun beginTransition(
        targetLine: Int,
        travelDirection: Float,
        viewportHeight: Float,
        scope: CoroutineScope,
        onSourceIsolated: () -> Unit = {},
        onTargetReady: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        if (previewActive) return
        val req = ++requestId
        animationJob?.cancel()

        // 1. 冻结实际来源窗口
        val source = currentPane
        source.isFrozen = true

        // 2. 创建独立目标窗口，使用独立的列表状态与行内动效对象
        val targetId = ++paneSequence
        val target = LyricWindowPane(
            id = targetId,
            initialLine = targetLine,
            listState = LazyListState(firstVisibleItemIndex = targetLine),
            playbackStep = LyricPlaybackStepMotion(targetLine),
            requestId = req,
        )

        val travel = travelDirection * viewportHeight.coerceAtLeast(300f)
        seekTravel = travel

        // 在一次一致的状态更新中发布来源、目标及 PREPARING
        Snapshot.withMutableSnapshot {
            outgoingPane = source
            currentPane = target
            state = LyricLayerTransitionState.PREPARING
        }

        // 3. 来源隔离完成：即可提交音频 seek，不需要等整个滑动动画结束
        onSourceIsolated()

        animationJob = scope.launch {
            try {
                // 4. 等待本次目标窗口完成布局和绘制确认
                target.ready.await()
                if (requestId != req) return@launch
                onTargetReady()

                // 5. 仍在准备状态时，先把动画位移设置为 travel
                seekOffset.snapTo(travel)

                // 6. 起始位移就绪后，再切换为 TRANSITIONING
                Snapshot.withMutableSnapshot {
                    state = LyricLayerTransitionState.TRANSITIONING
                }

                // 7. 执行现有 seekOffset.animateTo(0f, ...)
                seekOffset.animateTo(
                    0f,
                    lyricPlaybackMotionSpec(travel, LyricPlaybackMotionPurpose.SEEK),
                )

                // 8. 完成后由目标接管，再释放来源，主动补接最新播放句
                if (requestId == req) {
                    Snapshot.withMutableSnapshot {
                        state = LyricLayerTransitionState.NORMAL
                        outgoingPane = null
                        seekTravel = 0f
                        currentPane.currentLine = latestPlaybackLine
                    }
                    seekOffset.snapTo(0f)
                    onFinished()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (requestId == req && state != LyricLayerTransitionState.NORMAL) {
                    Snapshot.withMutableSnapshot {
                        state = LyricLayerTransitionState.NORMAL
                        outgoingPane = null
                        seekTravel = 0f
                    }
                    seekOffset.snapTo(0f)
                }
            }
        }
    }

    fun cancel() {
        animationJob?.cancel()
        animationJob = null
        state = LyricLayerTransitionState.NORMAL
        outgoingPane = null
        scopeResetOffset()
    }

    private fun scopeResetOffset() {
        // 保留当前位移不突变
    }
}

/**
 * 核对目标行与实际可见区域的交集。不能仅凭 visibleItemsInfo 中存在该行，就认为用户能看到它。
 */
internal fun isLyricTargetTrulyVisible(
    listState: LazyListState,
    targetIndex: Int,
): Boolean {
    val layout = listState.layoutInfo
    val item: LazyListItemInfo = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return false
    val viewportStart = layout.viewportStartOffset
    val viewportEnd = layout.viewportEndOffset
    val itemStart = item.offset
    val itemEnd = item.offset + item.size
    val overlapStart = maxOf(viewportStart, itemStart)
    val overlapEnd = minOf(viewportEnd, itemEnd)
    val overlapSize = overlapEnd - overlapStart
    return overlapSize >= minOf(item.size / 3, 20)
}
