package com.musicone.demo

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 后台起播不依赖页面动画的帧时钟或歌词缓存完成。 */
@Composable
internal fun PlaybackPresentationLifecycle(runtime: MusicPlaybackRuntime) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    DisposableEffect(runtime, lifecycle, activity) {
        fun update() {
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            runtime.rapidTrackSwitch.setPresentationActive(foreground)
            // 配置重建不是重新进入应用；重复前台生命周期事件也不会重复重置策略。
            if (foreground || activity?.isChangingConfigurations != true) runtime.preloadForegroundChanged(foreground)
            if (foreground) runtime.ensureServiceConnection()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            runtime.rapidTrackSwitch.setPresentationActive(false)
        }
    }
}
