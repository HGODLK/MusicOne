package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 后台起播不依赖页面动画的帧时钟或歌词缓存完成。 */
@Composable
internal fun PlaybackPresentationLifecycle(runtime: MusicPlaybackRuntime) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(runtime, lifecycle) {
        fun update() = runtime.rapidTrackSwitch.setPresentationActive(
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            runtime.rapidTrackSwitch.setPresentationActive(false)
        }
    }
}
