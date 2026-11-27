package com.musicone

/** 连续稳定帧检测器：只有连续多帧几何快照完全一致才判定为稳定，中途变化则重新计数。 */
internal class LyricStableFrames(private val required: Int = 3) {
    private var previous: Any? = null
    private var frames = 0
    fun observe(value: Any?): Boolean {
        frames = if (value == null) 0 else if (value == previous) frames + 1 else 1
        previous = value
        return frames >= required
    }
}
