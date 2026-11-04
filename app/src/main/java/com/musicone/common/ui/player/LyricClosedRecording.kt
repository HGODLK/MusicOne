package com.musicone

/** 关闭态按歌词时钟录制一次，父级 UI 的额外重绘不能突破歌词帧率。 */
internal class LyricClosedRecording {
    private var recorded = false
    private var lastFrame = Long.MIN_VALUE

    fun shouldRecord(idle: Boolean, frame: Long): Boolean {
        if (idle && recorded && frame == lastFrame) return false
        recorded = true
        lastFrame = frame
        return true
    }
}
