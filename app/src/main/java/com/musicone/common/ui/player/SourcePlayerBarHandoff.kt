package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 音源自建播放页关闭整页淡入时，迷你条上被声明共享的元素在过渡中让位给飞行中的包页面元素，
 * 与原生播放页一致；收起不飞回时保持显示，避免落地前元素消失。
 */
internal data class SourcePlayerBarHandoff(val keys: Set<String>, val onClose: Boolean, val glass: Boolean = false) {
    fun hides(key: String, motion: PageMotion): Boolean =
        (key in keys && motion.moving && (motion.wantsOpen || onClose)) ||
            // 声明玻璃时迷你条表面在开合两个方向都让位给飞行玻璃，收回落地后再显示。
            (glass && key == "surface" && motion.moving)
}

internal val LocalSourcePlayerBarHandoff = staticCompositionLocalOf<SourcePlayerBarHandoff?> { null }
