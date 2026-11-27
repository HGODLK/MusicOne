package com.musicone

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal enum class LyricPlaybackStepChange { NONE, ANIMATE, MULTI_STEP, RESET }

internal enum class LyricPlaybackMotionPurpose { PLAYBACK, SEEK, ALIGNMENT }

/** 自动滚动歌词统一使用临界阻尼弹簧，按滚动距离和场景（播放/跳转/对齐）调整刚度。 */
internal fun lyricPlaybackMotionSpec(
    travelPx: Float = 0f,
    purpose: LyricPlaybackMotionPurpose = LyricPlaybackMotionPurpose.PLAYBACK,
): FiniteAnimationSpec<Float> = musicSpring(
    stiffness = lyricPlaybackMotionStiffness(travelPx, purpose),
    visibilityThreshold = .1f,
)

internal fun lyricPlaybackMotionStiffness(
    travelPx: Float,
    purpose: LyricPlaybackMotionPurpose,
): Float = when (purpose) {
    LyricPlaybackMotionPurpose.PLAYBACK -> 200f
    LyricPlaybackMotionPurpose.SEEK -> when {
        abs(travelPx) <= 120f -> 260f
        abs(travelPx) <= 480f -> 220f
        else -> 185f
    }
    LyricPlaybackMotionPurpose.ALIGNMENT -> when {
        abs(travelPx) <= 120f -> 240f
        abs(travelPx) <= 480f -> 210f
        else -> 185f
    }
}

/** 歌词列表滚动和行内焦点动画（缩放/透明度/模糊/逐字）共用一个帧循环，各行独立维护位移与速度。 */
internal class LyricPlaybackStepMotion(
    initialIndex: Int,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var presentedIndex = initialIndex
    private var emphasisIndex = initialIndex
    val presentedLine get() = presentedIndex
    private var pendingIndex: Int? = null
    private var scrollPosition by mutableFloatStateOf(0f)
    private val scroll = LyricMotionValue(0f)
    private val rows = mutableMapOf<Int, LyricPlaybackRowMotion>()
    private val rowOwners = mutableMapOf<LyricPlaybackRowMotion, Int>()
    private val turnRetainedRows = mutableMapOf<Int, LyricPlaybackRowMotion>()
    private val wordObservers = mutableMapOf<LyricPlaybackRowMotion, () -> Long>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var wordBindings by mutableIntStateOf(0)
    private var frameTimeNanos: Long? = null
    private var runner: Job? = null
    private var trackFadeFrozen = false
    private var request: ScrollRequest? = null
    private var positionHeld = false
    private var seekPositionHeld = false
    private var hostPositionHeld = false
    private var turnPositionHeld = false
    private var wordsEntering = false

    /** 跨屏跳转的新窗口进场时调用，避免首帧按旧进度亮起后再突然跳变。 */
    fun enterWords(entering: Boolean) {
        if (wordsEntering == entering) return
        wordsEntering = entering
        rows.values.forEach { it.words.entering = entering }
        wake()
    }

    /** 切歌退场窗口专用：立刻停掉动画循环，把各行定格在当前画面不再复位。 */
    fun freezeForTrackFade() {
        trackFadeFrozen = true
        runner?.cancel()
        runner = null
        request?.finished?.complete(false)
        request = null
    }

    fun holdPosition(held: Boolean) {
        seekPositionHeld = held
        updatePositionHold()
    }

    fun holdHostPosition(held: Boolean) {
        hostPositionHeld = held
        updatePositionHold()
    }

    /** 转屏录制旧画面时同步冻结列表位移（不能等重组后再停），逐字高亮时钟保持正常推进。 */
    fun holdTurnPosition(held: Boolean) {
        turnPositionHeld = held
        if (!held) {
            turnRetainedRows.forEach { (index, row) -> removeRow(index, row) }
            turnRetainedRows.clear()
        }
        updatePositionHold()
    }

    private fun updatePositionHold() {
        val held = seekPositionHeld || hostPositionHeld || turnPositionHeld
        if (trackFadeFrozen) return
        if (positionHeld == held) return
        positionHeld = held
        if (held) {
            val retainedVelocity = if (request?.uniform == true) 0f else scroll.velocity
            request?.finished?.complete(false)
            request = null
            scroll.snapTo(scrollPosition, retainedVelocity)
        }
        wake()
    }

    fun row(index: Int): LyricPlaybackRowMotion = rows.getOrPut(index) {
        LyricPlaybackRowMotion(index, emphasisIndex, scrollPosition).also { row ->
            row.words.entering = wordsEntering
            request?.takeUnless { it.uniform }?.let { row.position.retarget(it.target, rowPositionSpec(index - presentedIndex)) }
        }
    }

    fun retain(index: Int, row: LyricPlaybackRowMotion) {
        // 在组件挂上后增加引用计数，若旧行刚被回收则重新登记回表里。
        rowOwners[row] = (rowOwners[row] ?: 0) + 1
        if (turnRetainedRows[index] === row) turnRetainedRows.remove(index)
        rows[index] = row
    }

    fun release(index: Int, row: LyricPlaybackRowMotion) {
        val owners = rowOwners[row] ?: 0
        if (owners > 1) {
            rowOwners[row] = owners - 1
            return
        }
        rowOwners.remove(row)
        // 转屏调整视口时同一行可能先移出再移回，冻结期间先暂存行状态，防止丢失明暗和逐字进度。
        if (turnPositionHeld && rows[index] === row) {
            turnRetainedRows[index] = row
            return
        }
        removeRow(index, row)
    }

    private fun removeRow(index: Int, row: LyricPlaybackRowMotion) {
        if (wordObservers.remove(row) != null) wordBindings++
        // 来源与目标可能短暂共用同一行；最后一个组件退出后才停止驱动。
        if (rows[index] === row) {
            rows.remove(index)
        }
    }

    fun configureWords(index: Int, ranges: List<LyricHighlightRange>, progressMs: (() -> Long)?,
                       enabled: Boolean, focused: Boolean, animate: Boolean, allowTail: Boolean = false) {
        if (trackFadeFrozen) return
        val row = row(index)
        row.words.configure(ranges, progressMs, enabled, focused, animate, allowTail)
        val clock = progressMs.takeIf { row.words.observesClock }
        if (clock == null) {
            if (wordObservers.remove(row) != null) wordBindings++
        } else if (wordObservers.put(row, clock) !== clock) {
            wordBindings++
        }
        if (row.words.running || row.words.finishingTail) wake()
    }

    fun holdWords(index: Int) {
        if (trackFadeFrozen) return
        val row = row(index)
        row.words.hold()
        if (wordObservers.remove(row) != null) wordBindings++
    }

    fun emphasize(index: Int, current: Int, animate: Boolean, playback: Boolean) {
        if (trackFadeFrozen) return
        row(index).emphasize(current, animate, playback)
        // 即使列表滚动还没到位，新滑入屏幕的行也要按当前焦点句来初始化高亮状态。
        emphasisIndex = current
        wake()
    }

    /** 记录当前真实焦点行，确保滑入视口的新行能直接拿到正确的焦点状态。 */
    fun presentEmphasis(current: Int) {
        if (!trackFadeFrozen) emphasisIndex = current
    }

    fun consume(targetIndex: Int, enabled: Boolean, resumeInterrupted: Boolean = false): LyricPlaybackStepChange {
        if (trackFadeFrozen) return LyricPlaybackStepChange.NONE
        val previous = presentedIndex
        val interrupted = pendingIndex == targetIndex
        presentedIndex = targetIndex
        // 正常播放跟随时先记下目标行，等滚动到位再确认；seek 完成后则直接确认。
        pendingIndex = targetIndex.takeIf { enabled && resumeInterrupted && (previous != it || interrupted) }
        return when {
            !enabled -> LyricPlaybackStepChange.RESET
            resumeInterrupted && interrupted -> LyricPlaybackStepChange.ANIMATE
            previous == targetIndex -> LyricPlaybackStepChange.NONE
            targetIndex == previous + 1 -> LyricPlaybackStepChange.ANIMATE
            else -> LyricPlaybackStepChange.MULTI_STEP
        }
    }

    fun completeStep(index: Int) {
        if (pendingIndex == index) pendingIndex = null
    }

    fun offsetPx(index: Int): Float = rows[index]?.let { scrollPosition - it.position.value } ?: 0f

    /** 屏内跳转接管时保留各行正在进行的位移动画，只换算坐标系并立即更新焦点。 */
    fun takeOverSeek(target: Int) {
        if (trackFadeFrozen) return
        reset()
        presentedIndex = target
        emphasisIndex = target
        rows.values.forEach {
            it.emphasize(target, animate = false, playback = false)
            it.advance(0L, finish = false, positionHeld = true)
        }
        wake()
    }

    fun reset(presentedIndex: Int? = null) {
        if (trackFadeFrozen) return
        presentedIndex?.let { this.presentedIndex = it; emphasisIndex = it; pendingIndex = null }
        val previousScroll = scrollPosition
        val previousVelocity = if (request?.uniform == true) 0f else scroll.velocity
        request?.finished?.complete(false)
        request = null
        // 屏内接管时把各行位移换算到新原点并保留速度，避免画面跳变；屏外准备的新窗口才直接清零。
        rows.values.forEach { row ->
            row.words.cancelTail()
            if (presentedIndex != null) {
                row.position.snapTo(0f)
                row.emphasize(presentedIndex, animate = false, playback = false)
            } else {
                row.position.snapTo(row.position.value - previousScroll,
                    row.position.velocity - previousVelocity)
                row.position.retarget(0f, rowPositionSpec(0))
            }
        }
        scrollPosition = 0f
        scroll.snapTo(0f)
        wake()
    }

    /** 列宽不变的转屏可保留各行剩余位移和速度（仅换算坐标）；需要重新换行时直接清零。 */
    fun prepareLayout(index: Int, preserveOffsets: Boolean = false) {
        if (trackFadeFrozen) return
        val previousScroll = scrollPosition
        val previousVelocity = if (request?.uniform == true) 0f else scroll.velocity
        presentedIndex = index
        pendingIndex = null
        request?.finished?.complete(false)
        request = null
        rows.forEach { (line, row) ->
            if (preserveOffsets) {
                row.position.snapTo(row.position.value - previousScroll, row.position.velocity - previousVelocity)
                row.position.retarget(0f, rowPositionSpec(line - index))
            } else row.position.snapTo(0f)
        }
        scrollPosition = 0f
        scroll.snapTo(0f)
        wake()
    }

    suspend fun move(current: Int, visibleIndices: List<Int>, travel: Float,
                     scrollBy: (Float) -> Float, canScroll: () -> Boolean = { true },
                     purpose: LyricPlaybackMotionPurpose = LyricPlaybackMotionPurpose.PLAYBACK): Boolean {
        if (trackFadeFrozen || !canScroll()) return false
        val uniform = purpose == LyricPlaybackMotionPurpose.SEEK
        val target = if (uniform) travel else scrollPosition + travel
        visibleIndices.forEach { row(it) }
        val operation = ScrollRequest(target, scrollBy, canScroll, uniform)
        request = operation
        if (uniform) scroll.snapTo(0f)
        scroll.retarget(target, lyricPlaybackMotionSpec(travel, purpose))
        if (!uniform) rows.forEach { (index, row) ->
            row.position.retarget(target, rowPositionSpec(index - current))
            row.emphasize(current, animate = true, playback = true)
        }
        if (!uniform) emphasisIndex = current
        wake()
        try {
            // 调用方挂起等待期间持有列表滚动权，由统一帧循环在主线程按帧推进滚动。
            return operation.finished.await()
        } finally {
            if (request === operation) {
                request = null
                if (uniform) scroll.snapTo(scrollPosition)
            }
        }
    }

    /** 词时钟发生跳转时调用；tapLine 是点击的目标句，其他行都按旧句处理。 */
    internal fun jumpWords(tapLine: Int?) {
        rows.forEach { (line, row) ->
            if (tapLine != null && (line == tapLine || line == tapLine + 1)) row.words.alignToClock()
            else row.words.snapToClock()
        }
        wake()
    }

    suspend fun run(tapLine: () -> Int? = { null }) = coroutineScope {
        if (trackFadeFrozen) return@coroutineScope
        val owner = coroutineContext[Job]
        runner = owner
        // 暂停期间也可能收到行内更新；恢复时不能沿用那次唤醒的旧时间。
        frameTimeNanos = nowNanos()
        // 每个歌词窗口统一订阅词时钟，行内及每个词都不创建额外帧协程。
        val clockJump = LyricWordClockJump(nowNanos)
        val wordUpdates = launch {
            snapshotFlow {
                // 重绑后的数值可能与旧时钟相同，绑定版本也必须触发唤醒。
                wordBindings to wordObservers.keys.mapNotNull { it.words.observedPosition() }
            }.collect { (_, positions) ->
                if (clockJump.update(positions.firstOrNull())) jumpWords(tapLine()) else wake()
            }
        }
        try {
            while (true) {
                wakeups.receive()
                while (request != null || rows.values.any { it.running(positionHeld) }) {
                    val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                    withFrameNanos { time ->
                        val previous = frameTimeNanos ?: time
                        val elapsed = (time - previous).coerceAtLeast(0L)
                        frameTimeNanos = maxOf(previous, time)
                        advance(if (scale > 0f) (elapsed / scale).toLong() else 0L, scale == 0f)
                    }
                }
                frameTimeNanos = null
            }
        } finally {
            if (runner === owner) runner = null
            wordUpdates.cancel()
            // 关闭态暂停后重新运行，不能把数秒空闲时间作为一个动画步长补进去。
            frameTimeNanos = null
        }
    }

    private fun advance(elapsed: Long, finish: Boolean) {
        // 每帧推进前再检查一次滚动权限，避免协程取消存在一帧延迟。
        if (request?.canScroll?.invoke() == false) {
            val retainedVelocity = if (request?.uniform == true) 0f else scroll.velocity
            request?.finished?.complete(false)
            request = null
            scroll.snapTo(scrollPosition, retainedVelocity)
        }
        val operation = request
        if (operation != null) {
            scroll.advance(elapsed, finish)
            val requested = scroll.value - (if (operation.uniform) operation.consumed else scrollPosition)
            val consumed = operation.scrollBy(requested)
            if (operation.uniform) operation.consumed += consumed else scrollPosition += consumed
            if (abs(requested - consumed) > .5f) {
                // 滚到列表顶部或底部滚不动时，按实际滚动距离截断终点，防止行图层一直偏着回不来。
                operation.target = if (operation.uniform) operation.consumed else scrollPosition
                scroll.snapTo(operation.target)
                if (!operation.uniform) rows.forEach { (index, row) ->
                    row.position.retarget(scrollPosition, rowPositionSpec(index - presentedIndex))
                }
            }
        }
        rows.values.forEach { row ->
            row.advance(elapsed, finish, positionHeld)
            if (!row.words.observesClock && wordObservers.remove(row) != null) wordBindings++
        }
        if (operation != null && !scroll.running && rows.values.none { it.position.running }) {
            request = null
            if (operation.uniform) scroll.snapTo(scrollPosition)
            operation.finished.complete(true)
        }
    }

    private fun wake() {
        if (frameTimeNanos == null) frameTimeNanos = nowNanos()
        wakeups.trySend(Unit)
    }

    private class ScrollRequest(var target: Float, val scrollBy: (Float) -> Float, val canScroll: () -> Boolean,
                              val uniform: Boolean = false) {
        var consumed = 0f
        val finished = CompletableDeferred<Boolean>()
    }
}

internal class LyricPlaybackRowMotion(val index: Int, current: Int, initialPosition: Float) {
    val words = LyricWordLightMotion()
    val position = LyricMotionValue(initialPosition)
    val focus = LyricMotionValue(if (index == current) 1f else 0f)
    val played = LyricMotionValue(if (index <= current) 1f else 0f)
    val opacity = LyricMotionValue(lyricRowOpacity(index, current))
    val blur = LyricMotionValue(lyricBlurDp(index, current))
    private var opacityTarget = lyricRowOpacity(index, current)
    private var opacitySpec: FiniteAnimationSpec<Float> = snap()
    val running get() = position.running || focus.running || played.running || opacity.running || blur.running || words.running
    fun running(positionHeld: Boolean) = (!positionHeld && position.running) ||
        focus.running || played.running || opacity.running || blur.running || words.running

    fun emphasize(current: Int, animate: Boolean, playback: Boolean) {
        focus.retarget(if (index == current) 1f else 0f,
            if (!animate) snap() else if (playback) musicSpring(200f, .001f) else musicMotion(240))
        // 已播放状态单独保留，焦点移往下一句时上一句不会再次缩小。
        played.retarget(if (index <= current) 1f else 0f,
            if (!animate) snap() else if (playback) musicSpring(200f, .001f) else musicMotion(240))
        opacityTarget = lyricRowOpacity(index, current)
        opacitySpec = if (!animate) snap() else if (playback) musicSpring(200f, .001f) else musicMotion(320)
        if (!playback) words.cancelTail()
        opacity.retarget(if (words.finishingTail) maxOf(.72f, opacityTarget) else opacityTarget, opacitySpec)
        blur.retarget(lyricBlurDp(index, current),
            if (!animate) snap() else if (playback) lyricRowBlurPlaybackSpec() else musicMotion(350))
    }

    fun advance(elapsed: Long, finish: Boolean, positionHeld: Boolean = false) {
        if (!positionHeld) position.advance(elapsed, finish)
        focus.advance(elapsed, finish)
        played.advance(elapsed, finish)
        if (words.running || words.finishingTail) words.advance(elapsed / 1_000_000_000f, finish)
        opacity.retarget(if (words.finishingTail) maxOf(.72f, opacityTarget) else opacityTarget, opacitySpec)
        opacity.advance(elapsed, finish)
        blur.advance(elapsed, finish)
    }
}

private fun lyricRowOpacity(index: Int, current: Int): Float =
    when {
        index == current -> 1f
        index == current + 1 -> .5f
        index == current + 2 -> .3f
        index > current + 2 -> .18f
        else -> .28f
    }

private fun rowPositionSpec(relative: Int): FiniteAnimationSpec<Float> =
    musicSpring(lyricPlaybackRowStiffness(relative), visibilityThreshold = .1f)

internal fun lyricPlaybackRowStiffness(relative: Int): Float = when {
    relative <= 0 -> 200f
    relative == 1 -> 170f
    relative == 2 -> 140f
    relative == 3 -> 115f
    relative == 4 -> 100f
    else -> 90f
}
