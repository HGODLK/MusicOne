package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** 只在窗口引用变化时更新录制资格，正常逐字帧继续复用原子图层。 */
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
    /** 已知显示交接失效时只补录一次引用，保留歌词窗口和行内动画。 */
    fun refresh() { revision++ }
}
