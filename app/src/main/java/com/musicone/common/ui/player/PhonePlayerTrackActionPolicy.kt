package com.musicone

/** 计算手机横屏时上一首/下一首图标的旋转角度，保证无论正反横屏，图标朝向都符合直觉。 */
internal object PhonePlayerTrackActionPolicy {
    fun previousOnLeft(rotation: Int): Boolean = rotation <= 0

    fun pressDirection(rotation: Int, previous: Boolean): Int =
        if (previous == previousOnLeft(rotation)) -1 else 1

    /** 复用按钮连续角度；正横屏逐步翻转半周，反横屏保持物理朝向，终点仍为上一首朝上。 */
    fun iconRotation(rotation: Int, elementRotation: Float,
        animatedRotation: Float = rotation.toFloat()): Float =
        2f * animatedRotation.coerceAtLeast(0f) - elementRotation
}
