package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** 跳转后旧句保持原样的时长，略长于行透明度的过渡，等它暗下去后再修正。 */
internal const val LYRIC_WORD_JUMP_HOLD_SECONDS = .45f

/** 明暗跟随演唱时间，共用行级帧循环。 */
internal class LyricWordLightMotion {
    private var ranges = emptyList<LyricHighlightRange>()
    private var samples = emptyList<LyricWordSample>()
    private var progressMs: (() -> Long)? = null
    private var enabled = false
    private var focused = false
    private var animate = true
    private var strength = LyricWordSample(0f)
    private var initialized = false
    val configured get() = initialized
    private var drawRevision by mutableIntStateOf(0)
    internal val renderRevision: Int get() = drawRevision
    internal var lightTimeNanos = 0L; private set
    internal val immediateLight: Boolean get() = !animate
    private var lightDeadlineNanos: Long? = null

    internal fun scheduleLightSample(deadline: Long) {
        lightDeadlineNanos = minOf(lightDeadlineNanos ?: deadline, deadline)
    }
    private var tailIndex = -1
    private var tailDeadlineMs = 0L
    private var clockJumped = false
    private var jumpHoldSeconds = 0f
    private var held = false

    /** 跨屏进场的窗口还没人看过，亮度直接等于当前进度，不保持也不追赶。 */
    var entering = false
    val finishingTail: Boolean get() = tailIndex >= 0

    fun configure(
        ranges: List<LyricHighlightRange>, progressMs: (() -> Long)?,
        enabled: Boolean, focused: Boolean, animate: Boolean, allowTail: Boolean = false,
    ) {
        this.progressMs = progressMs
        val position = progressMs?.invoke() ?: 0L
        val sameWords = this.ranges == ranges
        // 退场窗口被复用时不能留着旧状态，直接校正到当前进度。
        val released = held
        held = false
        // 跳转后才成为当前句的行不算旧句，立刻显示正确明暗。
        if (focused && !this.focused && jumpHoldSeconds > 0f) {
            jumpHoldSeconds = 0f
            clockJumped = true
        }
        if (!allowTail || !enabled || !animate || !sameWords || focused || released || jumpHoldSeconds > 0f) cancelTail()
        else if (this.focused && !focused) {
            val last = ranges.indexOfLast { it.word != null }
            val word = ranges.getOrNull(last)?.word
            // 只保留自然换句的末词；极短词或已经严重落后的收尾不继续排队。
            if (word != null && word.endMs - word.startMs >= 50L && position < word.endMs + 200L &&
                samples.getOrNull(last)?.moving(1f) == true) {
                tailIndex = last
                tailDeadlineMs = minOf(word.endMs + 250L, position + LYRIC_DISPLAY_LEAD_MS + 250L)
            }
        }
        val enabling = enabled && !this.enabled && strength.position == 0f
        if (!initialized || this.ranges != ranges || enabling) {
            samples = ranges.map { LyricWordSample(target(it.word, position)) }
        }
        // 跳转可能让没启用过的行直接成为当前句，要立刻显示正确明暗；自然换句时它已提前启用，走不到这里。
        if (enabling && focused && progressMs != null) {
            strength = LyricWordSample(1f)
            drawRevision++
        }
        this.ranges = ranges
        this.enabled = enabled && progressMs != null
        this.focused = focused && this.enabled
        this.animate = animate
        if (!initialized || !animate || released) {
            jumpHoldSeconds = 0f
            strength = LyricWordSample(if (this.enabled) 1f else 0f)
            samples = ranges.map { LyricWordSample(target(it.word, position)) }
            drawRevision++
        }
        initialized = true
    }

    // 只订阅仍需跟随播放时间的行；淡出的旧行继续观察，直到明暗收敛。
    val observesClock: Boolean get() = !held && progressMs != null && (enabled || strength.position > 0f)
    fun observedPosition(): Long? = if (observesClock) progressMs?.invoke() else null

    /** 词时钟被跳转时调用：正在唱的句子直接到位；其他句子先保持原样，等暗下去再跟上，不会瞬间填满。 */
    fun snapToClock() {
        if (!observesClock) return
        if (entering || lyricWordsCover(ranges, progressMs?.invoke() ?: return)) clockJumped = true
        else {
            jumpHoldSeconds = LYRIC_WORD_JUMP_HOLD_SECONDS
            cancelTail()
        }
    }

    /** 跨屏退场的窗口不再跟时钟，亮度停在当前样子随窗口淡出；下次 configure 解除。 */
    fun hold() {
        held = true
        jumpHoldSeconds = 0f
        clockJumped = false
        cancelTail()
    }

    val running: Boolean get() {
        if (held) return false
        if (clockJumped || jumpHoldSeconds > 0f || lightDeadlineNanos != null) return true
        if (strength.moving(if (enabled) 1f else 0f)) return true
        val position = observedPosition() ?: return false
        return samples.indices.any { samples[it].moving(target(ranges[it].word, position)) }
    }

    fun advance(seconds: Float, finish: Boolean = false) {
        if (held) return
        lightTimeNanos += (seconds.coerceAtLeast(0f) * 1_000_000_000L).toLong()
        if (jumpHoldSeconds > 0f && !finish && animate && !entering) {
            jumpHoldSeconds -= seconds.coerceAtLeast(0f)
            return
        }
        jumpHoldSeconds = 0f
        val lightDue = lightDeadlineNanos?.let { lightTimeNanos >= it || finish || !animate } == true
        if (lightDue) lightDeadlineNanos = null
        val position = progressMs?.invoke() ?: 0L
        val snap = finish || !animate || clockJumped || entering
        clockJumped = false
        if (finishingTail && (snap || position >= tailDeadlineMs ||
            (position >= (ranges[tailIndex].word?.endMs ?: 0L) && !samples[tailIndex].moving(1f)))) cancelTail()
        val strengthChanged = strength.follow(if (enabled) 1f else 0f, seconds, snap)
        var changed = lightDue || strengthChanged
        for (index in samples.indices) {
            val wordChanged = samples[index].follow(target(ranges[index].word, position), seconds, snap)
            changed = changed || wordChanged
        }
        if (finishingTail && position >= (ranges[tailIndex].word?.endMs ?: 0L) &&
            !samples[tailIndex].moving(1f)) {
            cancelTail()
            changed = true
        }
        if (changed) drawRevision++
    }

    fun cancelTail() { tailIndex = -1 }

    val drawing: Boolean get() {
        drawRevision
        return strength.position > 0f
    }

    fun alpha(index: Int, horizontalFraction: Float = .5f): Float {
        drawRevision
        if (ranges[index].word == null) return 1f
        return lyricWordSweepAlpha(samples[index].position, horizontalFraction, strength.position, animate)
    }

    internal fun sweepProgress(index: Int): Float = if (ranges[index].word == null) 1f else samples[index].position
    internal val sweepStrength: Float get() = strength.position
    internal val spatialLight: Boolean get() = animate

    private fun target(word: LyricWord?, position: Long): Float =
        if (word == null) 1f else ((lyricWordAlpha(word, position) - .7f) / .3f).coerceIn(0f, 1f)

}

/** 播放位置是否落在这句的逐字时间范围内。 */
internal fun lyricWordsCover(ranges: List<LyricHighlightRange>, position: Long): Boolean {
    val first = ranges.firstOrNull { it.word != null }?.word ?: return false
    val last = ranges.lastOrNull { it.word != null }?.word ?: return false
    return position in first.startMs..last.endMs
}

/** 柔边占整个词宽的 80%，从词外进入并完全离开，保证首尾亮度准确。 */
internal const val LYRIC_WORD_SWEEP_WIDTH = .8f

internal fun lyricWordSweepAlpha(progress: Float, fraction: Float, strength: Float, spatial: Boolean): Float {
    val bright = if (spatial) {
        ((progress * (1f + LYRIC_WORD_SWEEP_WIDTH) - fraction.coerceIn(0f, 1f)) /
            LYRIC_WORD_SWEEP_WIDTH).coerceIn(0f, 1f)
    } else progress
    return 1f - .3f * (1f - bright) * strength
}
