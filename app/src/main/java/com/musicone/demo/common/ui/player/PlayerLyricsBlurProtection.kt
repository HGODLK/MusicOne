package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.dp

internal data class LyricBlurProtection(val focusBottom: Float?, val allowWidening: Boolean) {
    companion object {
        // 交接中两层各自移动，沿用原过渡带，避免新增模糊影响来源或目标焦点。
        val HANDOFF = LyricBlurProtection(null, false)
    }
}

internal fun lyricBlurProtection(layout: LazyListLayoutInfo, focus: Int, offset: Float,
    stable: Boolean): LyricBlurProtection {
    if (!stable) return LyricBlurProtection.HANDOFF
    val item = layout.visibleItemsInfo.firstOrNull { it.index == focus }
    return LyricBlurProtection(item?.let {
        (it.offset - layout.viewportStartOffset + it.size).toFloat() + offset
    }, true)
}

/** 下沿保持原位；新增的上扩范围只使用焦点段落之外的空间。 */
internal fun playerControlsBlurStart(boundary: Float, originalHeight: Float, widenedHeight: Float,
    protection: LyricBlurProtection?, focusGap: Float): Float {
    val original = boundary - originalHeight
    if (protection?.allowWidening != true) return original
    val widened = boundary - widenedHeight
    return protection.focusBottom?.let { minOf(original, maxOf(widened, it + focusGap)) } ?: widened
}

internal val PLAYER_LYRICS_READING_ANCHOR = 52.dp
