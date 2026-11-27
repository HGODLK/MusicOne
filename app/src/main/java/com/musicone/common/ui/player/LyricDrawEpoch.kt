package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** 追踪歌词窗口结构变化的版本号，只有切窗或视口变化时才递增，普通逐字帧直接复用缓存图层。 */
internal class LyricDrawEpoch {
    private val owners = mutableMapOf<Any, List<Any?>>()
    var revision by mutableLongStateOf(0L)
        private set
    fun observe(owner: Any, windows: List<Any?>) {
        if (owners[owner] == windows) return
        owners[owner] = windows
        revision++
    }
    fun remove(owner: Any) { if (owners.remove(owner) != null) revision++ }
    /** 兜底刷新版本号，触发外层重新录制一次，不影响歌词窗口和行内动画。 */
    fun refresh() { revision++ }
}
