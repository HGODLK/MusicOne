package com.musicone

import kotlin.math.abs

/** 根据真实抬手时间计算收尾速度：手指停住后消除残余速度，微小抖动不会反转原方向。 */
internal class GestureReleaseVelocity(startTime: Long, private val density: Float = 1f,
    private val stablePlayerSettlement: Boolean = false) {
    private var lastMoveTime = startTime
    private var direction = 0f
    private val segments = ArrayDeque<Pair<Long, Float>>()
    private val settlingSegments = ArrayDeque<SettlingSegment>()
    private var previousTime = startTime
    private var distance = 0f
    private var reverseDistance = 0f
    private var reverseStartedAt = startTime
    private var pendingDelta = 0f
    private var pendingSince = startTime
    var effectiveDistancePx = 0f
        private set

    fun moved(time: Long, delta: Float) {
        distance += delta
        var acceptedDelta = delta
        var sampledFrom = previousTime
        if (direction * delta < 0f) {
            pendingDelta = 0f
            pendingSince = time
            if (reverseDistance == 0f) reverseStartedAt = previousTime
            reverseDistance += delta
            // 抬手前的轻微回勾忽略不计，累计反向移动达到 8dp 才算主动反划。
            if (abs(reverseDistance) < 8f * density) {
                previousTime = time
                return
            }
            acceptedDelta = reverseDistance
            sampledFrom = reverseStartedAt
            segments.clear()
            settlingSegments.clear()
        } else if (stablePlayerSettlement) {
            // 高频输入下细小位移必须累计，不能把持续慢拖误判成静止。
            pendingDelta += delta
            acceptedDelta = pendingDelta
            sampledFrom = pendingSince
        }
        if (direction * delta != 0f) reverseDistance = 0f
        if (abs(acceptedDelta) > .5f * density) {
            pendingDelta = 0f
            pendingSince = time
            segments.addLast((time - sampledFrom).coerceAtLeast(1L) to acceptedDelta)
            while (segments.size > 1 && segments.sumOf { it.first } > 100L) segments.removeFirst()
            lastMoveTime = time
            if (stablePlayerSettlement) {
                settlingSegments.addLast(SettlingSegment(time, (time - sampledFrom).coerceAtLeast(1L), acceptedDelta))
                while (settlingSegments.size > 1 && settlingSegments.first().end <= time - 100L)
                    settlingSegments.removeFirst()
            }
            direction = acceptedDelta
            effectiveDistancePx = distance
        }
        previousTime = time
    }

    /** 过滤抬手瞬间异常的末点位移；拖动过程中保持原始跟手，不影响主动反划和快划。 */
    fun finalDelta(time: Long, delta: Float): Float {
        if (!stablePlayerSettlement || settlingSegments.isEmpty()) return delta
        val elapsed = time - previousTime
        if (elapsed !in 0L..100L) return delta
        if (delta * direction < 0f && abs(reverseDistance + delta) < 8f * density) return 0f
        val speed = abs(stableSettlingVelocity(0f))
        val allowance = maxOf(8f * density, speed * elapsed.coerceAtLeast(8L) / 1000f * 3f + 2f * density)
        return if (abs(delta) > allowance) 0f else delta
    }

    fun released(time: Long, rawVelocity: Float, completed: Boolean): Float =
        velocityAtRelease(time, rawVelocity, completed, fadeStartMs = 40L, fadeEndMs = 100L)

    /** 计算收尾动画的初速度（允许更长的短暂停顿窗口，与防误触判定分开取值）。 */
    fun settlingVelocity(time: Long, rawVelocity: Float, completed: Boolean): Float =
        if (!stablePlayerSettlement) velocityAtRelease(time, rawVelocity, completed, 80L, 150L)
        else {
            if (!completed || time - lastMoveTime >= 150L) 0f
            else {
                val idle = (time - lastMoveTime).coerceAtLeast(80L)
                val decay = ((150L - idle).toFloat() / 70f).coerceIn(0f, 1f)
                (stableSettlingVelocity(rawVelocity) * decay).coerceIn(-8000f * density, 8000f * density)
            }
        }

    /** 按近期轨迹加权估算收尾速度，并用中位数限幅，避免抬手瞬间的异常采样点把速度拉爆。 */
    private fun stableSettlingVelocity(rawVelocity: Float): Float {
        if (settlingSegments.isEmpty()) return 0f
        val samples = settlingSegments.map { segment ->
            val duration = minOf(segment.duration, 100L - (lastMoveTime - segment.end)).coerceAtLeast(1L)
            val recency = (1f - (lastMoveTime - segment.end) / 100f).coerceIn(.25f, 1f)
            (segment.delta * 1000f / segment.duration) to duration * recency
        }
        val sorted = samples.sortedBy { abs(it.first) }
        val total = samples.sumOf { it.second.toDouble() }.toFloat()
        var cumulative = 0f
        val median = sorted.first { cumulative += it.second; cumulative >= total / 2f }.first
        val limit = if (samples.size >= 3) abs(median) * 2f else Float.POSITIVE_INFINITY
        val sampled = samples.sumOf { (it.first.coerceIn(-limit, limit) * it.second).toDouble() }.toFloat() / total
        // 只有系统拟合速度与近期轨迹接近时才混入权重，防止稀疏采样或抬手回勾把速度拉偏。
        return if (rawVelocity * sampled > 0f && abs(rawVelocity - sampled) <= abs(sampled) * .5f)
            sampled * .75f + rawVelocity * .25f else sampled
    }

    private data class SettlingSegment(val end: Long, val duration: Long, val delta: Float)

    private fun velocityAtRelease(time: Long, rawVelocity: Float, completed: Boolean,
                                  fadeStartMs: Long, fadeEndMs: Long): Float = when {
        !completed || time - lastMoveTime >= fadeEndMs -> 0f
        else -> {
            // 采样稀疏或抬手回勾会压低拟合速度，取拟合值与有效轨迹速度中同向的较大值。
            val sampled = if (segments.isNotEmpty())
                segments.sumOf { it.second.toDouble() }.toFloat() * 1000f / segments.sumOf { it.first }
            else 0f
            val velocity = if (rawVelocity * direction > 0f && abs(rawVelocity) > abs(sampled)) rawVelocity else sampled
            // 松手事件略晚于最后移动帧时保持速度；真正停住后才逐渐归零。
            val idleMs = (time - lastMoveTime).coerceAtLeast(0L)
            val decay = ((fadeEndMs - idleMs.coerceAtLeast(fadeStartMs)).toFloat() /
                (fadeEndMs - fadeStartMs)).coerceIn(0f, 1f)
            (if (velocity * direction < 0f) 0f else velocity * decay).coerceIn(-8000f * density, 8000f * density)
        }
    }
}
