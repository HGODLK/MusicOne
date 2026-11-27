package com.musicone.sourceintegration

import androidx.compose.runtime.*

/** 嵌入“我的”页首次露面之前先预热已有封面缓存，真正显示后切回正常的生命周期加载。 */
@Composable
internal fun rememberPrepareHiddenMyArtwork(enabled: Boolean, library: Any, interactive: Boolean): Boolean {
    if (!enabled) return false
    var shown by remember(library) { mutableStateOf(false) }
    SideEffect { if (interactive) shown = true }
    return enabled && !interactive && !shown
}
