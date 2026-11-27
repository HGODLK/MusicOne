package com.musicone

/** 性能提示会话接口：平台调用统一在反馈线程串行执行，关闭后忽略迟到的帧回报。 */
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
            // 帧指标不完整或没有可用预算时关闭当前提示会话，不沿用上一帧的目标。
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

/** 从整帧预算中扣除主线程以外的耗时，算出主线程的实际可用预算，避免把其他线程耗时算到主线程头上。 */
internal fun interactionUiFrameSample(deadline: Long, totalDuration: Long, stages: LongArray): InteractionUiFrameSample? {
    if (deadline <= 0L || totalDuration <= 0L) return null
    val actual = interactionUiWorkDuration(stages) ?: return null
    if (totalDuration < actual) return null
    val remaining = deadline - (totalDuration - actual)
    return if (remaining > 0L) InteractionUiFrameSample(remaining, actual) else null
}

/** 只累加已测得的主线程各阶段耗时，排除等待和 GPU 时间。 */
internal fun interactionUiWorkDuration(stages: LongArray): Long? {
    if (stages.isEmpty() || stages.any { it < 0L }) return null
    var total = 0L
    for (stage in stages) {
        if (stage > Long.MAX_VALUE - total) return null
        total += stage
    }
    return total.takeIf { it > 0L }
}
