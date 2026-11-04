package com.musicone

/** 横屏控件被反向窗口抵消，仍按物理竖屏坐标排成一行；观看上方须反算到这行的左右。 */
internal object PhonePlayerTrackActionPolicy {
    fun previousOnLeft(rotation: Int): Boolean = rotation <= 0

    fun pressDirection(rotation: Int, previous: Boolean): Int =
        if (previous == previousOnLeft(rotation)) -1 else 1

    /** 复用按钮连续角度；正横屏逐步翻转半周，反横屏保持物理朝向，终点仍为上一首朝上。 */
    fun iconRotation(rotation: Int, elementRotation: Float,
        animatedRotation: Float = rotation.toFloat()): Float =
        2f * animatedRotation.coerceAtLeast(0f) - elementRotation
}
