package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 跟随只响应用户的阅读方向，接近底部本身不能撤销向上翻阅的意图。 */
internal class AiChatFollowIntent {
    var following by mutableStateOf(true)
        private set
    private var towardBottom = false

    fun readEarlier() { following = false; towardBottom = false }
    fun userScroll(deltaY: Float) {
        if (deltaY == 0f) return
        towardBottom = deltaY < 0f
        if (!towardBottom) following = false
    }
    fun reachedTail(nearBottom: Boolean) {
        if (towardBottom && nearBottom) following = true
    }
}
