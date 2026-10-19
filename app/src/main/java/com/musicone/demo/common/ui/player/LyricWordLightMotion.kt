package com.musicone.demo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** 明暗跟随演唱时间，微缩放独立使用固定时长；共用行级帧循环。 */
internal class LyricWordLightMotion {
    private var ranges = emptyList<LyricHighlightRange>()
    private var samples = emptyList<LyricFlowSample>()
    private var scales = emptyList<LyricMotionValue>()
    private val scaleSpec = tween<Float>(500, easing = CubicBezierEasing(.4f, 0f, .6f, 1f))
    private var progressMs: (() -> Long)? = null
    private var enabled = false
    private var focused = false
    private var animate = true
    private var strength = LyricFlowSample(0f, 0f)
    private var scaleStrength = LyricFlowSample(0f, 0f)
    private var initialized = false
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
    val finishingTail: Boolean get() = tailIndex >= 0

    fun configure(
        ranges: List<LyricHighlightRange>, progressMs: (() -> Long)?,
        enabled: Boolean, focused: Boolean, animate: Boolean, allowTail: Boolean = false,
    ) {
        this.progressMs = progressMs
        val position = progressMs?.invoke() ?: 0L
        val sameWords = this.ranges == ranges
        if (!allowTail || !enabled || !animate || !sameWords || focused) cancelTail()
        else if (this.focused && !focused) {
            val last = ranges.indexOfLast { it.word != null }
            val word = ranges.getOrNull(last)?.word
            // 只保留自然换句的末词；极短词或已经严重落后的收尾不继续排队。
            if (word != null && word.endMs - word.startMs >= 50L && position < word.endMs + 200L &&
                samples.getOrNull(last)?.let { moving(it, 1f) } == true) {
                tailIndex = last
                tailDeadlineMs = minOf(word.endMs + 250L, position + LYRIC_DISPLAY_LEAD_MS + 250L)
            }
        }
        if (!initialized || this.ranges != ranges ||
            (enabled && !this.enabled && strength.position == 0f)) {
            samples = ranges.map { LyricFlowSample(target(it.word, position), 0f) }
            scales = ranges.map { LyricMotionValue(scaleTarget(it.word, position)) }
        }
        this.ranges = ranges
        this.enabled = enabled && progressMs != null
        this.focused = focused && this.enabled
        this.animate = animate
        if (!initialized || !animate) {
            strength = LyricFlowSample(if (this.enabled) 1f else 0f, 0f)
            scaleStrength = LyricFlowSample(if (this.focused) 1f else 0f, 0f)
            samples = ranges.map { LyricFlowSample(target(it.word, position), 0f) }
            scales = ranges.map { LyricMotionValue(scaleTarget(it.word, position)) }
            drawRevision++
        }
        initialized = true
    }

    // 只订阅仍需跟随播放时间的行；淡出的旧行继续观察，直到明暗收敛。
    val observesClock: Boolean get() = progressMs != null && (enabled || strength.position > 0f)
    fun observedPosition(): Long? = if (observesClock) progressMs?.invoke() else null

    val running: Boolean get() {
        if (lightDeadlineNanos != null) return true
        if (moving(strength, if (enabled) 1f else 0f) ||
            moving(scaleStrength, if (focused) 1f else 0f)) return true
        val position = observedPosition() ?: return false
        return samples.indices.any { moving(samples[it], target(ranges[it].word, position)) ||
            scales[it].running || scales[it].value != scaleTarget(ranges[it].word, position) }
    }

    fun advance(seconds: Float, finish: Boolean = false) {
        lightTimeNanos += (seconds.coerceAtLeast(0f) * 1_000_000_000L).toLong()
        val lightDue = lightDeadlineNanos?.let { lightTimeNanos >= it || finish || !animate } == true
        if (lightDue) lightDeadlineNanos = null
        val position = progressMs?.invoke() ?: 0L
        val snap = finish || !animate
        if (finishingTail && (snap || position >= tailDeadlineMs ||
            (position >= (ranges[tailIndex].word?.endMs ?: 0L) && !moving(samples[tailIndex], 1f)))) cancelTail()
        val nextStrength = follow(strength, if (enabled) 1f else 0f, seconds, snap)
        val nextScale = follow(scaleStrength, if (focused) 1f else 0f, seconds, snap)
        var changed = lightDue || strength.position != nextStrength.position || scaleStrength.position != nextScale.position
        strength = nextStrength
        scaleStrength = nextScale
        samples = samples.mapIndexed { index, sample ->
            val next = follow(sample, target(ranges[index].word, position), seconds, snap)
            changed = changed || sample.position != next.position
            next
        }
        scales.forEachIndexed { index, scale ->
            val before = scale.value
            val target = scaleTarget(ranges[index].word, position)
            if (snap) scale.snapTo(target) else {
                scale.retarget(target, scaleSpec)
                scale.advance((seconds * 1_000_000_000L).toLong())
            }
            changed = changed || before != scale.value
        }
        if (finishingTail && position >= (ranges[tailIndex].word?.endMs ?: 0L) &&
            !moving(samples[tailIndex], 1f)) {
            cancelTail()
            changed = true
        }
        if (changed) drawRevision++
    }

    fun cancelTail() { tailIndex = -1 }

    val drawing: Boolean get() {
        drawRevision
        return strength.position > 0f || scaleStrength.position > 0f
    }

    fun alpha(index: Int, horizontalFraction: Float = .5f): Float {
        drawRevision
        if (ranges[index].word == null) return 1f
        val progress = samples[index].position
        // 整词同时提亮，左侧稍早、右侧稍晚；首尾一致，不形成硬边扫光。
        val stagger = if (animate) .4f * progress * (1f - progress) *
            (1f - 2f * horizontalFraction.coerceIn(0f, 1f)) else 0f
        return 1f - .2f * (1f - progress - stagger) * strength.position
    }

    fun scale(index: Int): Float {
        drawRevision
        if (ranges[index].word == null) return 1f
        return 1f - .015f * (1f - scales[index].value) * scaleStrength.position
    }

    private fun target(word: LyricWord?, position: Long): Float =
        if (word == null) 1f else ((lyricWordAlpha(word, position) - .8f) / .2f).coerceIn(0f, 1f)

    private fun scaleTarget(word: LyricWord?, position: Long): Float =
        if (word == null || position >= word.startMs) 1f else 0f

    private fun moving(sample: LyricFlowSample, target: Float): Boolean =
        abs(sample.position - target) > .0001f || abs(sample.velocity) > .002f

    private fun follow(sample: LyricFlowSample, target: Float, seconds: Float, finish: Boolean): LyricFlowSample {
        if (finish || !moving(sample, target)) return LyricFlowSample(target, 0f)
        // 响应 22 时，阶跃约 180ms 完成九成变化；切换目标保留当前位置与速度。
        val next = advanceLyricFlow(sample, target, seconds, response = 22f)
        val bounded = next.position.coerceIn(0f, 1f)
        return if (bounded != next.position) LyricFlowSample(bounded, 0f) else next
    }
}
