package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 只转发已有歌词帧；同一系统帧的多个动画回调只请求一次宿主绘制。所有入口均在界面线程。 */
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
