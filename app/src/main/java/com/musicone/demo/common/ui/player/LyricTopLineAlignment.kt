package com.musicone.demo

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState

/** 在屏外准备完整顶行，落位后由现有阅读锚点滚动自然滑入顶部遮挡区。 */
internal suspend fun LazyListState.revealTopLineForEntrance() {
    scroll(MutatePriority.PreventUserInput) {
        val viewport = layoutInfo
        val delta = viewport.visibleItemsInfo.firstNotNullOfOrNull {
            lyricExitScrollDelta(it.offset, it.size, viewport.viewportStartOffset)
        } ?: 0f
        if (delta != 0f) scrollBy(delta)
    }
}
