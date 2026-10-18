package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 只接管开合中途的 seek；位置暂存和实时焦点分别处理。 */
internal class LyricIntermediateSeek(initialRevision: Long, initialCommitRevision: Long = 0L) {
    var engaged by mutableStateOf(false)
        private set
    var holding by mutableStateOf(false)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    var handledTap by mutableLongStateOf(initialRevision)
        private set
    private var observedTap = initialRevision
    private var observedCommit = initialCommitRevision
    private var wasSeeking = false
    private var requestedLine: Int? = null
    private var protectingCommit = false
    private var pendingRequest: ProgressLyricSeek? = null
    private var owner = 0L

    fun attachOwner(): Long = ++owner

    fun releaseOwner(token: Long) { if (owner == token) cancel() }

    fun willOwn(seeking: Boolean, intermediate: Boolean, request: ProgressLyricSeek?,
        commit: ProgressLyricSeekCommit? = null): Boolean =
        engaged || intermediate && (seeking || request != null && request.revision != observedTap ||
            commit != null && commit.revision != observedCommit)

    fun update(current: Int, seeking: Boolean, intermediate: Boolean,
        gestureHeld: Boolean, enabled: Boolean, request: ProgressLyricSeek?, tapLine: Int?, commitLine: Int? = null,
        commit: ProgressLyricSeekCommit? = null) {
        val newTap = request != null && request.revision != observedTap
        val newCommit = commit != null && commit.revision != observedCommit
        if (request != null) observedTap = request.revision
        if (commit != null) observedCommit = commit.revision
        if (!enabled) {
            cancel()
            wasSeeking = seeking
            return
        }
        if ((seeking && (!wasSeeking || !engaged) || newTap || newCommit) && (intermediate || engaged)) {
            engaged = true
            revision++
            requestedLine = if (newTap) tapLine else null
            if (newTap || seeking && !wasSeeking) {
                // 新输入取代上一轮提交保护，向后拖动也必须立即跟随最新预览句。
                protectingCommit = false
                pendingRequest?.targetReady?.complete(Unit)
                pendingRequest = null
            }
            if (newTap) {
                pendingRequest = request
                handledTap = request.revision
                request.sourceIsolated.complete(Unit)
            }
        }
        if (engaged && commitLine != null && (newCommit || protectingCommit)) {
            requestedLine = commitLine
            protectingCommit = true
        } else if (protectingCommit) {
            // 正式进度未追上时维持目标保护，追上或自然推进到下一句后解除，不长期锁死。
            val target = requestedLine
            if (target == null || current >= target) {
                requestedLine = null
                protectingCommit = false
            }
        } else if (requestedLine == current) requestedLine = null
        wasSeeking = seeking
        holding = engaged && (seeking || gestureHeld)
    }

    fun target(current: Int): Int = requestedLine ?: current

    fun targetReady(requestRevision: Long = revision) {
        if (requestRevision == revision) pendingRequest?.targetReady?.complete(Unit)
    }

    fun finish() {
        targetReady()
        pendingRequest = null
        engaged = false
        holding = false
        requestedLine = null
        protectingCommit = false
    }

    fun cancel() {
        if (engaged) revision++
        finish()
    }
}

@Composable
internal fun LyricIntermediateSeekEffect(
    state: LyricIntermediateSeek,
    controller: LyricLayerTransitionController,
    current: Int,
    seeking: Boolean,
    intermediate: Boolean,
    gestureHeld: Boolean,
    enabled: Boolean,
    request: ProgressLyricSeek?,
    tapLine: Int?,
    commitLine: Int?,
    commit: ProgressLyricSeekCommit?,
    progress: () -> Float,
    windowOffset: () -> Float,
    windowVelocity: () -> Float,
    onTakeover: () -> Unit,
    onOwnership: (Boolean) -> Unit,
    onAligned: () -> Unit,
) {
    val latestCurrent by rememberUpdatedState(current)
    val latestIntermediate by rememberUpdatedState(intermediate)
    val latestProgress by rememberUpdatedState(progress)
    val latestWindowOffset by rememberUpdatedState(windowOffset)
    val latestWindowVelocity by rememberUpdatedState(windowVelocity)
    val latestAligned by rememberUpdatedState(onAligned)
    val latestCommitLine by rememberUpdatedState(commitLine)
    val pane = controller.currentPane
    SideEffect {
        val wasEngaged = state.engaged
        state.update(current, seeking, intermediate, gestureHeld, enabled, request, tapLine, commitLine, commit)
        onOwnership(state.engaged && enabled)
        if (!wasEngaged && state.engaged) onTakeover()
        if (state.engaged && enabled) {
            // 接管期间保持目标句为唯一有效目标，杜绝旧焦点闪现与错误灭灯。
            controller.updatePlaybackLine(state.target(current), presentDuringTransition = true)
        }
        pane.playbackStep.holdPosition(state.holding)
    }
    DisposableEffect(pane) { onDispose { pane.playbackStep.holdPosition(false) } }
    DisposableEffect(controller) { onDispose { onOwnership(false) } }
    // 连续输入只改目标，不取消并重建寻焦，保留同一列表的位移和速度。
    LyricAnimationEffect(controller, enabled) {
        if (!enabled) return@LyricAnimationEffect
        val owner = state.attachOwner()
        var transition: Job? = null
        try {
            while (true) {
                snapshotFlow { state.engaged }.first { it }
                while (state.engaged) {
                    val source = controller.currentPane
                    source.transitionHistory.releaseForSeek()
                    val result = alignIntermediateLyricSeek(source,
                        target = { state.target(latestCurrent) },
                        held = { state.holding },
                        owned = { state.engaged && controller.currentPane === source },
                        transitioning = { controller.state != LyricLayerTransitionState.NORMAL || controller.previewActive },
                        canRelease = { latestCommitLine == null && !latestIntermediate && latestProgress() >= 1f },
                        windowOffset = { latestWindowOffset() },
                        windowVelocity = { latestWindowVelocity() })
                    when (result) {
                        LyricIntermediateSeekResult.ALIGNED -> {
                            // 当前实测位置已经到位，旧入场锚点不能再启动一次补滚。
                            latestAligned()
                            val target = state.target(latestCurrent)
                            controller.updatePlaybackLine(target, presentDuringTransition = true)
                            source.playbackStep.consume(target, enabled = true)
                            state.finish()
                        }
                        LyricIntermediateSeekResult.FAR -> {
                            val target = state.target(latestCurrent)
                            val requestRevision = state.revision
                            transition = controller.beginTransition(target,
                                if (target > source.listState.firstVisibleItemIndex) 1f else -1f,
                                source.listState.layoutInfo.viewportSize.height.toFloat(), this,
                                onTargetReady = { state.targetReady(requestRevision) },
                                prepareTarget = { it.transitionHistory.releaseForSeek() },
                                positionHeld = { state.holding },
                                settleOnCancellation = true)
                            // 不等待展开或跨屏动画结束，目标列表立即参与同一寻焦过程。
                        }
                        LyricIntermediateSeekResult.RELEASED -> Unit
                    }
                    withFrameNanos { }
                }
            }
        } finally {
            withContext(NonCancellable) { transition?.cancelAndJoin() }
            state.releaseOwner(owner)
        }
    }
}
