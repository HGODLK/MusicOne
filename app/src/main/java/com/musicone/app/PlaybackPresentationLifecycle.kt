package com.musicone

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 同步前后台状态给播放器，后台切歌和起播不依赖界面动画或歌词缓存。 */
@Composable
internal fun PlaybackPresentationLifecycle(runtime: MusicPlaybackRuntime) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    DisposableEffect(runtime, lifecycle, activity) {
        fun update() {
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            runtime.rapidTrackSwitch.setPresentationActive(foreground)
            // 旋转屏幕等配置重建不算退到后台，避免误触发前后台切换逻辑。
            if (foreground || activity?.isChangingConfigurations != true) runtime.onForegroundChanged(foreground)
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
