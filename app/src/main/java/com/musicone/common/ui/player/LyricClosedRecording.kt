package com.musicone

/** 歌词关闭态下按歌词帧时钟节流录制，避免父级 UI 重绘带动歌词超出设定帧率。 */
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
