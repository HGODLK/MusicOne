package com.musicone.demo

/** 词级绘制值最高 60fps；低刷新率跟随实际帧，逐字亮度门控，端点及时收敛。 */
internal class LyricWordLightSampler(private val lowerEndpoint: Float = .7f) {
    private var sampledAt = Long.MIN_VALUE
    private var left = Float.NaN
    private var right = Float.NaN

    fun update(timeNanos: Long, left: Float, right: Float, immediate: Boolean = false,
        record: (Float, Float) -> Unit): Long? {
        if (this.left == left && this.right == right) return null
        val endpoint = left == right && (left == lowerEndpoint || left == 1f)
        if (sampledAt != Long.MIN_VALUE && !immediate && !endpoint &&
            timeNanos - sampledAt < 16_666_666L) return sampledAt + 16_666_666L
        record(left, right)
        this.left = left
        this.right = right
        sampledAt = timeNanos
        return null
    }
}
