package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 是否自动滚到底只看用户的滑动方向，用户刚往上翻时就算还离底部很近也不要抢着拉回去。 */
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
