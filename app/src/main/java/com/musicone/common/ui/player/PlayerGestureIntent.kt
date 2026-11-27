package com.musicone

/** 记录拖动过程中的净位移和最大回退距离，防止抬手瞬间的轻微回勾被误判成取消。 */
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

/** 判断松手后是展开还是收起：优先看是否拖过阈值，未过线时再按长距离拖动或快划速度决定。 */
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
    // 没过中线时，要么拖够 commitDistanceDp，要么快划同时满足距离和速度门槛，才算切换。
    if (distance >= commitDistanceDp ||
        (distance >= flingDistanceDp && openingVelocityDp * direction >= flingVelocityDp)) {
        return !original
    }
    return original
}

/** 过滤掉与目标方向相反的初速度，避免松手确定方向后还先反向弹一下。 */
internal fun playerGestureSettlingVelocity(open: Boolean, velocity: Float): Float =
    if (open) velocity.coerceAtLeast(0f) else velocity.coerceAtMost(0f)
