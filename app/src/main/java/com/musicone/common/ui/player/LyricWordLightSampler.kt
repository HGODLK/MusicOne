package com.musicone

/** 逐字高亮采样节流器：最高按 60fps 更新绘制值，低刷屏跟随实际帧率并在端点及时收敛。 */
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
