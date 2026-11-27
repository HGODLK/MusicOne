package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 记录全屏播放页当前是否在前台可见，可见时自动隐藏桌面歌词；应用退后台时视为不可见。 */
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
