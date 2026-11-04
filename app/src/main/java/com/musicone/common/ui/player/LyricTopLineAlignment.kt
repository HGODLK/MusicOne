package com.musicone

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect

/** 在屏外准备完整顶行，入场时由同一展开进度将列表送回阅读锚点。 */
internal suspend fun LazyListState.revealTopLineForEntrance() {
    scroll(MutatePriority.PreventUserInput) {
        val viewport = layoutInfo
        val delta = viewport.visibleItemsInfo.firstNotNullOfOrNull {
            lyricExitScrollDelta(it.offset, it.size, viewport.viewportStartOffset)
        } ?: 0f
        if (delta != 0f) scrollBy(delta)
    }
}

/** 保存列表交接状态，防止父级到终点的同一帧又启动播放跟随补位。 */
@Stable
internal class LyricTopLineAlignment {
    private var preparationRevision = 0L
    private var preparedLine: Int? = null
    var playbackScroll by mutableStateOf<LyricEntrancePlaybackScroll?>(null)
        private set
    var followingPlayback by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set

    fun prepare(line: Int? = null) {
        followingPlayback = false
        preparationRevision++
        preparedLine = line
        pending = true
    }

    fun acceptSeekAlignment() {
        // 中间态 seek 已在同一位置驱动中完成，不再补播旧的首开锚点。
        preparationRevision++
        preparedLine = null
        playbackScroll?.release()
        followingPlayback = true
        pending = false
    }

    suspend fun align(pane: LyricWindowPane, progress: () -> Float) {
        // 基线固定在准备时已呈现的句子，之后的换句不能挤进入场剩余进度。
        val revision = preparationRevision
        val list = pane.listState
        val origin = preparedLine ?: pane.playbackStep.presentedLine
        preparedLine = null
        pending = true
        snapshotFlow { pane.listHost.ready && list.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val lease = pane.listHost.scrollLease()
        list.scroll(MutatePriority.PreventUserInput) {
            val offset = lyricNaturalStepTravel(list.layoutInfo, origin) ?: return@scroll
            val shared = LyricEntrancePlaybackScroll(origin, offset, { scrollBy(it) }, lease)
            playbackScroll = shared
            try {
                snapshotFlow { progress().coerceIn(0f, 1f) to shared.playbackUsers }
                    .takeWhile { (fraction, users) ->
                        if (!shared.canScroll() || preparationRevision != revision) return@takeWhile false
                        shared.moveEntrance(fraction)
                        // 展开结束只收掉自己的补偿，尚在运行的自然换句继续持有同一滚动权。
                        fraction < 1f || users > 0
                    }.collect()
            } finally {
                shared.release()
                if (playbackScroll === shared) playbackScroll = null
            }
        }
        if (preparationRevision == revision && lease()) {
            followingPlayback = true
            pending = false
        }
    }
}

/** 接管时读取当前画面，不补播等待滚动权期间已经走过的展开进度。 */
internal class LyricEntranceAlignmentSession {
    private var target = -1
    private var measured = false
    private var travel: LyricEntranceTravel? = null

    fun moveTo(index: Int, progress: Float, offset: Float, measured: Boolean,
        scrollBy: (Float) -> Float) {
        if (target != index || travel == null || (!this.measured && measured)) {
            target = index
            this.measured = measured
            travel = LyricEntranceTravel(progress, offset)
        }
        travel!!.moveTo(progress, scrollBy)
    }
}

/** 反向、拖动和跨句重新接管时，起点始终是当前实际偏移。 */
internal class LyricEntranceTravel(private val start: Float, private val offset: Float) {
    private var applied = 0f

    fun moveTo(progress: Float, scrollBy: (Float) -> Float) {
        // 与收起对齐一样累计实际消费量，避免逐帧读取整数 offset 引入来回一像素抖动。
        applied += scrollBy(offset - remainingOffset(progress) - applied)
    }

    fun remainingOffset(progress: Float): Float = if (progress >= 1f || start >= 1f) 0f else {
        offset * ((1f - progress) / (1f - start)).coerceIn(0f, 1f)
    }
}

@Composable
internal fun rememberLyricTopLineAlignment(
    pane: LyricWindowPane,
    enabled: Boolean,
    active: Boolean,
    prepareWhileHidden: Boolean,
    openingAlignment: CompletableDeferred<Unit>?,
    exitAlignment: CompletableDeferred<Unit>?,
    progress: () -> Float,
    positionOwned: Boolean = false,
): LyricTopLineAlignment {
    val alignment = remember(pane) { LyricTopLineAlignment() }
    val latestProgress by rememberUpdatedState(progress)
    LyricAnimationEffect(alignment, pane.listHost.revision, enabled, active, prepareWhileHidden, openingAlignment, exitAlignment, positionOwned) {
        if (enabled && active && !prepareWhileHidden && exitAlignment == null && !positionOwned) {
            openingAlignment?.await()
            if (latestProgress() < 1f && alignment.followingPlayback) alignment.prepare()
            while (true) {
                snapshotFlow { alignment.pending || latestProgress() < 1f && !alignment.followingPlayback }.first { it }
                // 首次打开仍需等屏外定位完成；手势反向不重置列表或重播准备阶段。
                snapshotFlow { latestProgress() }.first { it > 0f }
                alignment.align(pane, { latestProgress() })
            }
        }
    }
    return alignment
}
