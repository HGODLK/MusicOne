package com.musicone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

@Composable
internal fun FlowingArtworkAtmosphere(frame: PlayerArtworkFrame, direction: () -> TrackTransitionDirection) {
    val atmosphereMotion = LocalPlayerAtmosphereMotion.current
    val motion = LocalPlayerMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rotation by rememberUpdatedState(LocalPhonePlayerRotation.current)
    val reduced = ExperiencePreferences.options.reduceMotion
    val advancing = playerAtmosphereCanAdvance(motion?.phase) && LocalPlayerVisible.current
    PlayerAtmosphereAnimationEffect(lifecycle, reduced, advancing) {
        if (reduced || !advancing) return@PlayerAtmosphereAnimationEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                if (playerAtmosphereCanAdvance(motion?.phase)) atmosphereMotion.advance(now - previous)
                previous = now
            }
        }
    }
    // 新旧封面颜色在同一套九色场中插值，避免交叉渐变把全屏绘制成本翻倍。
    ArtworkAtmosphereTransition(frame, atmosphereMotion, direction, { rotation }, Modifier.fillMaxSize())
}
