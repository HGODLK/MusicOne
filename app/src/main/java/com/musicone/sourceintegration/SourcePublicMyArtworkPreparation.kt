package com.musicone.sourceintegration

import androidx.compose.runtime.*

/** 只在一级“我的”第一次可见前准备缓存，之后恢复原生命周期订阅及图片加载。 */
@Composable
internal fun rememberPrepareHiddenMyArtwork(enabled: Boolean, library: Any, interactive: Boolean): Boolean {
    if (!enabled) return false
    var shown by remember(library) { mutableStateOf(false) }
    SideEffect { if (interactive) shown = true }
    return enabled && !interactive && !shown
}
