package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
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
    val listHost: LyricListHost = LyricListHost(),
) {
    var currentLine by mutableIntStateOf(initialLine)
    val ready = CompletableDeferred<Unit>()
    var drawnFirstFrame = false
    val transitionHistory = LyricTransitionHistory()
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
        newLyricWindowPane(1L, initialLine),
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
    private var trackFadeFrozen = false

    var previewActive by mutableStateOf(false)
        private set
    var previewPreparing by mutableStateOf(false)
        private set
    var previewPane by mutableStateOf<LyricWindowPane?>(null)
        private set
    private var previewTarget: Pair<Int, CompletableDeferred<Unit>>? = null
    private var previewOwner = 0L
    var displayFallback by mutableStateOf(false)
        private set
    var displayRevision by mutableIntStateOf(0)
        private set
    internal var preparationDeadline: () -> Long = { Long.MAX_VALUE }
    internal var refreshDisplay: () -> Unit = {}

    private fun recoverDisplay(pane: LyricWindowPane) {
        displayFallback = true
        refreshDisplay()
        LyricDisplayDiagnostics.recovery("寻焦绘制超时", pane)
    }
    val drawingOffset: Float get() = if (state == LyricLayerTransitionState.TRANSITIONING) seekOffset.value else 0f
    fun attachPreviewOwner(): Long = ++previewOwner

    suspend fun previewTo(
        line: Int,
        position: suspend (LyricWindowPane, Int) -> Unit = { pane, index -> pane.listState.scrollToItem(index) },
    ) {
        if (trackFadeFrozen) return
        if (!previewActive) {
            previewActive = true
            previewPreparing = state != LyricLayerTransitionState.NORMAL
            // 先使旧请求失效，旧任务的 finally 不得清除接管中的来源画面。
            requestId++
            val interrupted = animationJob
            animationJob = null
            if (previewPreparing) {
                previewPane = newLyricWindowPane(++paneSequence, line, requestId = requestId)
            }
            interrupted?.cancelAndJoin()
        }
        val pane = previewPane ?: currentPane
        val owner = previewOwner
        val budget = LyricPreparationBudget()
        val ready = CompletableDeferred<Unit>()
        previewTarget = line to ready
        if (previewPreparing) displayRevision++
        suspend fun placePreview() {
            position(pane, line)
            pane.playbackStep.consume(line, enabled = false)
            // 隐藏目标直接准备完整焦点，已显示的预览仍保留原行内接续效果。
            pane.playbackStep.reset(if (previewPreparing) line else null)
            pane.currentLine = line
        }
        val prepared = if (previewPreparing) {
            // 未挂载列表的 scrollToItem 也可能等待首轮布局，定位和绘制必须共用预算。
            withTimeoutOrNull(budget.remainingMillis(preparationDeadline())) {
                placePreview()
                budget.await(ready, preparationDeadline())
            } == true
        } else { placePreview(); true }
        if (!prepared) {
            if (owner != previewOwner || !previewActive || previewTarget?.second !== ready) return
            pane.currentLine = line
            recoverDisplay(pane)
        }
        if (owner != previewOwner || !previewActive || previewTarget?.second !== ready) return
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

    suspend fun finishPreview(owner: Long? = null) {
        if (owner != null && owner != previewOwner) return
        if (trackFadeFrozen) return
        if (!previewActive) return
        // 隐藏预览尚未就绪时取消，继续显示已经呈现的窗口，不能放出未绘制目标。
        val displayed = if (state == LyricLayerTransitionState.PREPARING) outgoingPane ?: currentPane else currentPane
        seekOffset.snapTo(0f)
        if (owner != null && owner != previewOwner) return
        Snapshot.withMutableSnapshot {
            currentPane = displayed
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

    /** 转屏准备取得唯一位置所有权；旧 seek 的收尾不能再覆盖本轮目标。 */
    suspend fun settleForLayout(line: Int) {
        if (trackFadeFrozen) return
        val req = ++requestId
        displayFallback = false
        previewOwner++
        val displayed = if (state == LyricLayerTransitionState.PREPARING) outgoingPane ?: currentPane else currentPane
        val previous = animationJob
        animationJob = null
        previous?.cancelAndJoin()
        if (requestId != req) return
        seekOffset.snapTo(0f)
        if (requestId != req) return
        Snapshot.withMutableSnapshot {
            currentPane = displayed
            currentPane.currentLine = line
            currentPane.isFrozen = false
            outgoingPane = null
            previewActive = false
            previewPreparing = false
            previewPane = null
            state = LyricLayerTransitionState.NORMAL
            seekTravel = 0f
        }
        previewTarget = null
    }

    fun updatePlaybackLine(line: Int, present: Boolean = true, presentDuringTransition: Boolean = false) {
        if (trackFadeFrozen) return
        latestPlaybackLine = line
        if (present && (state == LyricLayerTransitionState.NORMAL || presentDuringTransition) && !previewActive) {
            currentPane.currentLine = line
        }
    }

    /** 跨曲退场保留已显示的 seek 姿态，旧任务的清理不能把它复位到另一个端点。 */
    fun freezeForTrackFade() {
        if (trackFadeFrozen) return
        trackFadeFrozen = true
        requestId++
        animationJob?.cancel()
        animationJob = null
        currentPane.isFrozen = true
        outgoingPane?.isFrozen = true
        previewPane?.isFrozen = true
        listOfNotNull(currentPane, outgoingPane, previewPane).distinct().forEach {
            it.playbackStep.freezeForTrackFade()
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
        purpose: LyricPlaybackMotionPurpose = LyricPlaybackMotionPurpose.SEEK,
        prepareTarget: suspend (LyricWindowPane) -> Unit = {},
        positionHeld: (() -> Boolean)? = null,
    ): Job? {
        if (previewActive || trackFadeFrozen) return null
        val req = ++requestId
        displayFallback = false
        val budget = LyricPreparationBudget()
        animationJob?.cancel()
        val preparation = beginLyricTracePhase("MusicOne:seek-prepare")

        // 1. 冻结实际来源窗口
        // 连续寻焦替换隐藏目标时，保留仍在屏幕上的来源，不能把未入场目标当成旧页。
        val source = if (state == LyricLayerTransitionState.PREPARING) outgoingPane ?: currentPane else currentPane
        source.isFrozen = true

        // 2. 创建独立目标窗口，使用独立的列表状态与行内动效对象
        val targetId = ++paneSequence
        val target = newLyricWindowPane(targetId, targetLine, requestId = req)

        val travel = travelDirection * viewportHeight.coerceAtLeast(300f)
        // 在一次一致的状态更新中发布来源、目标及 PREPARING
        Snapshot.withMutableSnapshot {
            seekTravel = travel
            outgoingPane = source
            currentPane = target
            state = LyricLayerTransitionState.PREPARING
        }

        // 3. 来源隔离完成：即可提交音频 seek，不需要等整个滑动动画结束
        onSourceIsolated()

        // 所有入口先进入收尾保护，首帧前被转屏或重新开关接管也必须解除来源冻结。
        animationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var completed = false
            var motion: LyricTracePhase? = null
            try {
                // 4. 等待本次目标窗口完成布局和绘制确认
                if (!budget.await(target.ready, preparationDeadline())) {
                    if (requestId != req) return@launch
                    recoverDisplay(target)
                }
                prepareTarget(target)
                if (positionHeld != null) snapshotFlow { positionHeld() }.first { !it }
                if (requestId != req) return@launch
                onTargetReady()
                endLyricTracePhase(preparation)
                motion = beginLyricTracePhase("MusicOne:seek-window")

                // 5. 仍在准备状态时，先把动画位移设置为 travel
                seekOffset.snapTo(travel)

                // 6. 起始位移就绪后，再切换为 TRANSITIONING
                Snapshot.withMutableSnapshot {
                    state = LyricLayerTransitionState.TRANSITIONING
                }

                // 7. 执行现有 seekOffset.animateTo(0f, ...)
                if (positionHeld == null) seekOffset.animateTo(0f, lyricPlaybackMotionSpec(travel, purpose))
                else runLyricHeldLayerTravel(seekOffset, travel, purpose, positionHeld)

                // 8. 完成后由目标接管，再释放来源，主动补接最新播放句
                if (requestId == req) {
                    Snapshot.withMutableSnapshot {
                        state = LyricLayerTransitionState.NORMAL
                        outgoingPane = null
                        seekTravel = 0f
                        currentPane.currentLine = latestPlaybackLine
                    }
                    seekOffset.snapTo(0f)
                    completed = true
                    onFinished()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                endLyricTracePhase(motion ?: preparation)
                if (!completed && requestId == req) {
                    // 普通寻焦同样回到最后已显示窗口，不能留下不可交互的冻结来源。
                    withContext(NonCancellable) {
                        seekOffset.snapTo(0f)
                        if (requestId == req) Snapshot.withMutableSnapshot {
                            if (state == LyricLayerTransitionState.PREPARING) {
                                currentPane = source
                            }
                            currentPane.isFrozen = false
                            state = LyricLayerTransitionState.NORMAL
                            outgoingPane = null
                            seekTravel = 0f
                        }
                    }
                }
            }
        }.also { operation ->
            operation.invokeOnCompletion { endLyricTracePhase(preparation) }
        }
        return animationJob
    }

    fun cancel() {
        if (trackFadeFrozen) return
        requestId++
        animationJob?.cancel()
        animationJob = null
        if (state == LyricLayerTransitionState.PREPARING) currentPane = outgoingPane ?: currentPane
        currentPane.isFrozen = false
        state = LyricLayerTransitionState.NORMAL
        outgoingPane = null
        seekTravel = 0f
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
