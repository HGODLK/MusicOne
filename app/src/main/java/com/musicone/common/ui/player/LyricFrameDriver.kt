package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 合并同一系统帧里的多次歌词动画回调，每帧只触发一次宿主重绘（仅主线程调用）。 */
internal class LyricFrameDriver {
    private val hosts = linkedMapOf<Any, (Long) -> Unit>()
    private var lastFrame = Long.MIN_VALUE

    fun register(owner: Any, onFrame: (Long) -> Unit) { hosts[owner] = onFrame }
    fun remove(owner: Any) { hosts.remove(owner) }

    fun onFrame(time: Long) {
        if (time == lastFrame) return
        lastFrame = time
        hosts.values.forEach { it(time) }
    }
}

internal val LocalLyricFrameDriver = staticCompositionLocalOf<LyricFrameDriver?> { null }
