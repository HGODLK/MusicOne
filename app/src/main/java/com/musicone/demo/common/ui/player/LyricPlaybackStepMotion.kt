package com.musicone.demo

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal enum class LyricPlaybackStepChange { NONE, ANIMATE, MULTI_STEP, RESET }

internal enum class LyricPlaybackMotionPurpose { PLAYBACK, SEEK, ALIGNMENT }

/** 所有自动歌词位移使用同一临界阻尼风格，仅按距离与用途调整响应速度。 */
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

/** 位移和焦点强调共用一个帧循环，各行保留独立的位置、速度和收敛速度。 */
internal class LyricPlaybackStepMotion(
    initialIndex: Int,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var presentedIndex = initialIndex
    private var scrollPosition by mutableFloatStateOf(0f)
    private val scroll = LyricMotionValue(0f)
    private val rows = mutableMapOf<Int, LyricPlaybackRowMotion>()
    private val rowOwners = mutableMapOf<LyricPlaybackRowMotion, Int>()
    private val wordObservers = mutableMapOf<LyricPlaybackRowMotion, () -> Long>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var wordBindings by mutableIntStateOf(0)
    private var frameTimeNanos: Long? = null
    private var request: ScrollRequest? = null
    private var positionHeld = false

    fun holdPosition(held: Boolean) {
        if (positionHeld == held) return
        positionHeld = held
        if (held) {
            request?.finished?.complete(Unit)
            request = null
            scroll.snapTo(scrollPosition, scroll.velocity)
        }
        wake()
    }

    fun row(index: Int): LyricPlaybackRowMotion = rows.getOrPut(index) {
        LyricPlaybackRowMotion(index, presentedIndex, scrollPosition).also { row ->
            request?.let { row.position.retarget(it.target, rowPositionSpec(index - presentedIndex)) }
        }
    }

    fun retain(index: Int, row: LyricPlaybackRowMotion) {
        // 只在组件提交后登记持有者；若旧组件已先退出，接回界面记住的原对象。
        rowOwners[row] = (rowOwners[row] ?: 0) + 1
        rows[index] = row
    }

    fun release(index: Int, row: LyricPlaybackRowMotion) {
        val owners = rowOwners[row] ?: 0
        if (owners > 1) {
            rowOwners[row] = owners - 1
            return
        }
        rowOwners.remove(row)
        if (wordObservers.remove(row) != null) wordBindings++
        // 来源与目标可能短暂共用同一行；最后一个组件退出后才停止驱动。
        if (rows[index] === row) {
            rows.remove(index)
        }
    }

    fun configureWords(index: Int, ranges: List<LyricHighlightRange>, progressMs: (() -> Long)?,
                       enabled: Boolean, focused: Boolean, animate: Boolean, allowTail: Boolean = false) {
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

    fun emphasize(index: Int, current: Int, animate: Boolean, playback: Boolean) {
        row(index).emphasize(current, animate, playback)
        wake()
    }

    fun consume(targetIndex: Int, enabled: Boolean): LyricPlaybackStepChange {
        val previous = presentedIndex
        presentedIndex = targetIndex
        return when {
            !enabled -> LyricPlaybackStepChange.RESET
            previous == targetIndex -> LyricPlaybackStepChange.NONE
            targetIndex == previous + 1 -> LyricPlaybackStepChange.ANIMATE
            else -> LyricPlaybackStepChange.MULTI_STEP
        }
    }

    fun offsetPx(index: Int): Float = rows[index]?.let { scrollPosition - it.position.value } ?: 0f

    /** 可见 seek 接管只转换位置坐标，焦点立即更新，不清掉尚在运动的行位移。 */
    fun takeOverSeek(target: Int) {
        reset()
        presentedIndex = target
        rows.values.forEach {
            it.emphasize(target, animate = false, playback = false)
            it.advance(0L, finish = false, positionHeld = true)
        }
        wake()
    }

    fun reset(presentedIndex: Int? = null) {
        presentedIndex?.let { this.presentedIndex = it }
        val previousScroll = scrollPosition
        val previousVelocity = scroll.velocity
        request?.finished?.complete(Unit)
        request = null
        // 可见接管时只转换坐标，保留图层残量及相对速度；屏外准备才直接清零。
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

    suspend fun move(current: Int, visibleIndices: List<Int>, travel: Float,
                     scrollBy: (Float) -> Float) {
        val target = scrollPosition + travel
        visibleIndices.forEach { row(it) }
        val operation = ScrollRequest(target, scrollBy)
        request = operation
        scroll.retarget(target, lyricPlaybackMotionSpec(travel))
        rows.forEach { (index, row) ->
            row.position.retarget(target, rowPositionSpec(index - current))
            row.emphasize(current, animate = true, playback = true)
        }
        wake()
        try {
            // 调用方在等待期间持有列表滚动权，统一帧回调只在同一 UI 线程推进滚动。
            operation.finished.await()
        } finally {
            if (request === operation) request = null
        }
    }

    suspend fun run() = coroutineScope {
        // 每个歌词窗口统一订阅词时钟，行内及每个词都不创建额外帧协程。
        val wordUpdates = launch {
            snapshotFlow {
                // 重绑后的数值可能与旧时钟相同，绑定版本也必须触发唤醒。
                wordBindings to wordObservers.keys.mapNotNull { it.words.observedPosition() }
            }.collect { wake() }
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
            wordUpdates.cancel()
            // 关闭态暂停后重新运行，不能把数秒空闲时间作为一个动画步长补进去。
            frameTimeNanos = null
        }
    }

    private fun advance(elapsed: Long, finish: Boolean) {
        val operation = request
        if (operation != null) {
            scroll.advance(elapsed, finish)
            val requested = scroll.value - scrollPosition
            val consumed = operation.scrollBy(requested)
            scrollPosition += consumed
            if (abs(requested - consumed) > .5f) {
                // 到达列表边界时，以实际消费量作为终点，不能留下永久的图层补偿。
                operation.target = scrollPosition
                scroll.snapTo(scrollPosition)
                rows.forEach { (index, row) ->
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
            operation.finished.complete(Unit)
        }
    }

    private fun wake() {
        if (frameTimeNanos == null) frameTimeNanos = nowNanos()
        wakeups.trySend(Unit)
    }

    private class ScrollRequest(var target: Float, val scrollBy: (Float) -> Float) {
        val finished = CompletableDeferred<Unit>()
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
            if (!animate) snap() else if (playback) musicSpring(185f, .01f) else musicMotion(350))
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
