package com.musicone

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect

/** 歌词展开前先在屏外把顶部露出的一行补全，入场时随展开进度平滑滚回阅读锚点。 */
internal suspend fun LazyListState.revealTopLineForEntrance() {
    scroll(MutatePriority.PreventUserInput) {
        val viewport = layoutInfo
        val delta = viewport.visibleItemsInfo.firstNotNullOfOrNull {
            lyricExitScrollDelta(it.offset, it.size, viewport.viewportStartOffset)
        } ?: 0f
        if (delta != 0f) scrollBy(delta)
    }
}

/** 管理歌词展开入场时的列表对齐状态，避免展开结束瞬间和播放跟随打架。 */
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
        // 如果展开中途发生了 seek，列表已由 seek 逻辑对齐，不再补跑旧的入场对齐。
        preparationRevision++
        preparedLine = null
        playbackScroll?.release()
        followingPlayback = true
        pending = false
    }

    suspend fun align(pane: LyricWindowPane, progress: () -> Float) {
        // 入场对齐锁定在准备时的那一行，展开途中的自然换句走独立的播放滚动，不混在一起。
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
                        // 展开进度到 1 后，如果此时正好有自然换句在滚动，等它滚完再释放滚动权。
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

/** 入场对齐会话：接管滚动时从当前进度起步，不补播之前已经走过的部分。 */
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

/** 按当前进度线性插值剩余偏移，确保拖动或反向接管时从实际位置平滑过渡。 */
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
                // 首次打开需等屏外定位完成；手势中途反向时直接继续，不重新走准备流程。
                snapshotFlow { latestProgress() }.first { it > 0f }
                alignment.align(pane, { latestProgress() })
            }
        }
    }
    return alignment
}
