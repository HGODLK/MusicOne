package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

internal data class PhoneLyricsStageGesture(val owner: Any, val motion: PhoneLyricsMotion,
    val region: PhoneLyricsTouchRegion, val enabled: Boolean, val visible: Boolean, val change: (Boolean) -> Unit)
internal data class PhoneLyricsControlsGesture(val owner: Any, val state: PlayerControlsVisibilityState,
    val enabled: Boolean, val height: () -> Float, val motion: PageMotion,
    val requested: Boolean, val active: Boolean)

/** 手势监听放在歌词和前景控件的共同父级，按钮命中仍由原生层级决定。 */
internal class PhoneLyricsSurfaceGestures {
    var stage by mutableStateOf<PhoneLyricsStageGesture?>(null)
    var controls by mutableStateOf<PhoneLyricsControlsGesture?>(null)
    var root: LayoutCoordinates? = null
    var stageCoordinates: LayoutCoordinates? = null
    var controlsCoordinates: LayoutCoordinates? = null
    private fun origin(value: LayoutCoordinates?): Offset? {
        val host = root ?: return null
        if (!host.isAttached || value?.isAttached != true) return null
        return host.localPositionOf(value, Offset.Zero)
    }
    fun stageOrigin(): Offset = origin(stageCoordinates) ?: Offset.Zero
    fun controlsBounds(): Rect? = origin(controlsCoordinates)?.let {
        val size = controlsCoordinates!!.size
        Rect(it, Size(size.width.toFloat(), size.height.toFloat()))
    }
}

@Composable
internal fun Modifier.phoneLyricsSurfaceStageGesture(surface: PhoneLyricsSurface?, motion: PhoneLyricsMotion,
    region: PhoneLyricsTouchRegion, enabled: Boolean, visible: Boolean, change: (Boolean) -> Unit): Modifier {
    if (surface == null) return phoneLyricsGesture(motion, region, enabled, visible, change)
    val gestures = surface.gestures
    val owner = remember(gestures) { Any() }
    val latestChange by rememberUpdatedState(change)
    val changeState = remember(owner) { { target: Boolean -> latestChange(target) } }
    SideEffect { gestures.stage = PhoneLyricsStageGesture(owner, motion, region, enabled, visible, changeState) }
    DisposableEffect(gestures, owner) { onDispose {
        if (gestures.stage?.owner === owner) { gestures.stage = null; gestures.stageCoordinates = null }
    } }
    return onGloballyPositioned { gestures.stageCoordinates = it }
}

@Composable
internal fun Modifier.phoneLyricsSurfaceControlsGesture(surface: PhoneLyricsSurface?, state: PlayerControlsVisibilityState,
    enabled: Boolean, height: () -> Float, motion: PageMotion, requested: Boolean, active: Boolean): Modifier {
    if (surface == null) return playerIdleFrameRate(motion, requested, state, active)
        .playerControlsHintTouches(state).playerControlsRestoreGesture(state, enabled, height)
    val gestures = surface.gestures
    val owner = remember(gestures) { Any() }
    val latestHeight by rememberUpdatedState(height)
    val readHeight = remember(owner) { { latestHeight() } }
    SideEffect { gestures.controls = PhoneLyricsControlsGesture(owner, state, enabled, readHeight, motion, requested, active) }
    DisposableEffect(gestures, owner) { onDispose {
        if (gestures.controls?.owner === owner) { gestures.controls = null; gestures.controlsCoordinates = null }
    } }
    return onGloballyPositioned { gestures.controlsCoordinates = it }
}

@Composable
internal fun Modifier.phoneLyricsSurfaceGestures(gestures: PhoneLyricsSurfaceGestures): Modifier {
    if (LocalPhonePlayerRotation.current != 0) return this
    var result = this
    gestures.controls?.let {
        result = result.playerIdleFrameRate(it.motion, it.requested, it.state, it.active)
            .playerControlsHintTouches(it.state).playerControlsRestoreGesture(it.state,
            it.enabled, it.height, viewport = gestures::controlsBounds)
    }
    gestures.stage?.let {
        result = result.phoneLyricsGesture(it.motion, it.region, it.enabled, it.visible, it.change,
            positionOffset = gestures::stageOrigin)
    }
    return result
}
