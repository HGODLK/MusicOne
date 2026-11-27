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
 * 同曲内跨屏跳转歌词时的双窗口切换控制器：
 * 冻结当前画面，在屏外准备好目标位置后再平滑滑入，最后释放旧窗口。
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
            // 先递增 requestId 让旧任务失效，防止旧任务的 finally 把正在接管的画面清掉。
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
            // 屏外准备的新窗口直接重置到目标行焦点；当前可见窗口的预览则保留行内过渡效果。
            pane.playbackStep.reset(if (previewPreparing) line else null)
            pane.currentLine = line
        }
        val prepared = if (previewPreparing) {
            // 未挂载列表调用 scrollToItem 可能会挂起等首轮布局，所以定位和绘制共用同一超时预算。
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
        // 如果屏外预览还没准备好就取消，切回当前已显示的旧窗口，避免闪出空白页。
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

    /** 转屏准备时中止正在进行的跳转动画并锁定当前行，防止旧 seek 收尾覆盖新布局。 */
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

    /** 切歌退场时冻结当前画面状态，防止旧的 seek 清理逻辑把退场中的歌词复位。 */
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

        // 1. 冻结当前屏幕上的旧窗口（连续跳转时不能把还在屏外准备的半成品当成旧窗口）
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

        // 3. 旧画面已冻结，此时就可以提交音频 seek，不用等滑动动画播完
        onSourceIsolated()

        // 立刻启动协程进入 try-finally 保护，确保首帧前若被转屏或取消打断也能解除旧窗口冻结。
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

                // 8. 动画结束后切到目标窗口、释放旧窗口，并同步最新的播放行
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
                    // 跳转被取消时恢复到最后显示的窗口并解除冻结，避免列表卡死无法交互。
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
