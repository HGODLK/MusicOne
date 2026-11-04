package com.musicone

import kotlin.math.ceil

/** 容量在布局确定时固定；末端余数与原图一致，移动到最底端仍保持公共像素网格。 */
internal fun lyricBlurSampleCapacity(maxBandHeight: Float, height: Float, radius: Float): Float {
    val padding = radius * 3f + 8f
    val capacity = ceil((maxBandHeight + padding * 2f) / 80f) * 80f + 80f + height % 80f
    return capacity.coerceIn(0f, height)
}

/** 平移固定容量的采样窗口，完整包含原有卷积留白，不在开合途中调整纹理尺寸。 */
internal fun lyricBlurSampleOrigin(top: Float, capacity: Float, height: Float): Float =
    top.coerceIn(0f, (height - capacity).coerceAtLeast(0f))
