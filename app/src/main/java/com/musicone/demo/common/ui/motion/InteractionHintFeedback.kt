package com.musicone.demo

/** 平台调用在同一个回调线程串行执行，关闭后不再接受迟到帧。 */
internal interface InteractionHintSession {
    fun target(nanos: Long)
    fun report(nanos: Long)
    fun close()
}

internal class InteractionHintFeedback(
    private val onDisabled: () -> Unit = {},
    private val create: (Long) -> InteractionHintSession?,
) {
    private var session: InteractionHintSession? = null
    private var target = 0L
    private var active = false
    private var disabled = false
    private var closed = false

    fun setActive(active: Boolean) {
        if (closed || disabled) return
        this.active = active
        safely {
            if (!active) release()
        }
    }

    fun report(deadline: Long, totalDuration: Long, stages: LongArray) {
        if (closed || disabled || !active) return
        val sample = interactionUiFrameSample(deadline, totalDuration, stages)
        safely {
            // 无可用预算或指标不完整时撤销提示，不延用上一帧的宽松目标。
            if (sample == null) { release(); return@safely }
            if (session == null) {
                target = sample.target
                session = create(target)
                if (session == null) { disabled = true; onDisabled(); return@safely }
            }
            val current = session ?: return@safely
            if (target != sample.target) { current.target(sample.target); target = sample.target }
            current.report(sample.actual)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        safely { release() }
    }

    private fun release() {
        val previous = session
        session = null
        previous?.close()
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) {
            val notify = !disabled
            disabled = true
            try { release() } catch (_: RuntimeException) { }
            if (notify) onDisabled()
        }
    }
}

internal data class InteractionUiFrameSample(val target: Long, val actual: Long)

/** 从整帧预算中保留未计入 UI 样本的实测耗时，不把这部分伪报成主线程工作。 */
internal fun interactionUiFrameSample(deadline: Long, totalDuration: Long, stages: LongArray): InteractionUiFrameSample? {
    if (deadline <= 0L || totalDuration <= 0L) return null
    val actual = interactionUiWorkDuration(stages) ?: return null
    if (totalDuration < actual) return null
    val remaining = deadline - (totalDuration - actual)
    return if (remaining > 0L) InteractionUiFrameSample(remaining, actual) else null
}

/** 只汇总已实测的主线程阶段，不把帧间隔、等待和 GPU 时间冒充主线程工作。 */
internal fun interactionUiWorkDuration(stages: LongArray): Long? {
    if (stages.isEmpty() || stages.any { it < 0L }) return null
    var total = 0L
    for (stage in stages) {
        if (stage > Long.MAX_VALUE - total) return null
        total += stage
    }
    return total.takeIf { it > 0L }
}
