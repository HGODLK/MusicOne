package com.musicone.demo

/** 独立记录实际拖动和回退距离，抬手速度不能把短促回勾放大成主动撤销。 */
internal class PlayerGestureDisplacement(private val original: Boolean) {
    var distancePx = 0f
        private set
    private var furthest = 0f
    val reverseDistancePx get() = furthest - forwardDistance
    private val forwardDistance get() = if (original) -distancePx else distancePx

    fun move(openingDeltaPx: Float) {
        distancePx += openingDeltaPx
        furthest = maxOf(furthest, forwardDistance)
    }
}

/** 位置优先：过线后按位置收尾，长距离和短划分别提供提交路径，取消保留已到达的端点。 */
internal fun playerGestureTarget(
    progress: Float,
    openingVelocityDp: Float,
    openingDistanceDp: Float,
    original: Boolean,
    completed: Boolean,
    threshold: Float,
    commitDistanceDp: Float = 96f,
    reverseDistanceDp: Float = 0f,
    flingDistanceDp: Float = 24f,
    flingVelocityDp: Float = 500f,
): Boolean {
    if (!completed) return when {
        progress <= .1f -> false
        progress >= .9f -> true
        else -> original
    }
    val positionTarget = progress >= threshold
    if (positionTarget != original) return positionTarget
    // 已明显拉回原侧才撤销；短促抬手回勾不影响前面完成的拖动。
    if (reverseDistanceDp >= 24f) return original
    val direction = if (original) -1f else 1f
    val distance = openingDistanceDp * direction
    // 慢拖未过线时不凭微小位移提交；短划必须同时满足有效位移与同向速度门槛。
    if (distance >= commitDistanceDp ||
        (distance >= flingDistanceDp && openingVelocityDp * direction >= flingVelocityDp)) {
        return !original
    }
    return original
}

/** 收尾只携带朝向所选端点的速度，避免已确定目标后先反向弹一下。 */
internal fun playerGestureSettlingVelocity(open: Boolean, velocity: Float): Float =
    if (open) velocity.coerceAtLeast(0f) else velocity.coerceAtMost(0f)
