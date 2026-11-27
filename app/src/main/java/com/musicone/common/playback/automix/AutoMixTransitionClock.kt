package com.musicone

/** AutoMixTransition 的实现：时钟取自输出端实际写出的片段位置（暂停即停），位置与视觉进度按 10ms 曲线插值。 */
internal class AutoMixTransitionClock(
    private val prepared: AutoMixPrepared,
    private val clock: () -> Long,
) : AutoMixTransition {
    override val plan = AutoMixPlan(prepared.from, prepared.to.track, prepared.kind,
        prepared.enterMs, prepared.seamMs, prepared.fromEndMs, prepared.totalMs)

    override fun clockMs(): Long = clock().coerceIn(0L, plan.totalMs)

    override fun toPositionMs(): Long =
        (curve(prepared.mapB, clockMs()) * 1000.0).toLong() + prepared.bFirstMediaUs / 1000L

    override fun fromPositionMs(): Long {
        val end = prepared.from.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return ((curve(prepared.mapA, clockMs()) * 1000.0).toLong() + prepared.aFirstMediaUs / 1000L).coerceAtMost(end)
    }

    override fun visualProgress(clockMs: Long): Float = curve(prepared.visual, clockMs).toFloat().coerceIn(0f, 1f)

    private fun curve(points: DoubleArray, ms: Long): Double {
        if (points.isEmpty()) return 0.0
        val t = ms.coerceAtLeast(0L) / 10.0
        val i = t.toInt().coerceIn(0, points.size - 1)
        if (i >= points.size - 1) return points.last()
        return points[i] + (points[i + 1] - points[i]) * (t - i)
    }
}
