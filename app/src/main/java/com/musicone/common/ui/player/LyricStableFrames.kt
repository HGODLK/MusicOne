package com.musicone

/** 有效不等于稳定；排版、坐标或端点变化后必须重新累计连续帧。 */
internal class LyricStableFrames(private val required: Int = 3) {
    private var previous: Any? = null
    private var frames = 0
    fun observe(value: Any?): Boolean {
        frames = if (value == null) 0 else if (value == previous) frames + 1 else 1
        previous = value
        return frames >= required
    }
}
