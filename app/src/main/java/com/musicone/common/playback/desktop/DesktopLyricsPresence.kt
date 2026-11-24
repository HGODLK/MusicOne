package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 界面只上报“前台且全屏播放页打开”，桌面歌词据此让位；退到后台一律视为不可见。 */
internal object DesktopLyricsPresence {
    private val mutablePlayerVisible = MutableStateFlow(false)
    val playerVisible = mutablePlayerVisible.asStateFlow()
    fun update(visible: Boolean) { mutablePlayerVisible.value = visible }
}

@Composable
internal fun DesktopLyricsPresenceEffect(playerMounted: Boolean) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = playerMounted && state.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(visible) {
        DesktopLyricsPresence.update(visible)
        onDispose { DesktopLyricsPresence.update(false) }
    }
}
