package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** 原生和音源自建播放页共用常亮生命周期，退场完成后恢复宿主原值。 */
@Composable
internal fun PlayerKeepScreenOn(visible: Boolean, playing: Boolean) {
    if (!visible) return
    val view = LocalView.current
    DisposableEffect(view, playing, PlaybackOptions.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = playing && PlaybackOptions.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
}
