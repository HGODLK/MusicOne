package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo

internal val LocalPlayerFrameRatePolicy = staticCompositionLocalOf<PlayerFrameRatePolicy?> { null }

@Composable
internal fun PlayerHighFrameRateWhile(busy: () -> Boolean) {
    val policy = LocalPlayerFrameRatePolicy.current ?: return
    val latest by rememberUpdatedState(busy)
    DisposableEffect(policy) {
        val owner = Any()
        policy.registerBusy(owner) { latest() }
        onDispose { policy.remove(owner) }
    }
}

private class PlayerTouchFrameRate { var pressed = false }

/** 仅由单栏播放器安装；横屏双栏歌词、菜单、队列及整页转场继续使用原帧率。 */
@Composable
internal fun Modifier.playerIdleFrameRate(motion: PageMotion, requested: Boolean,
    controls: PlayerControlsVisibilityState, enabled: Boolean): Modifier {
    val policy = LocalPlayerFrameRatePolicy.current ?: return this
    val lyrics = LocalRetainedPhoneLyricsMotion.current ?: return this
    val visible = LocalPlayerVisible.current
    val window = LocalWindowInfo.current
    val queue = LocalPlayerQueueState.current
    val navigation = LocalEntityNavigation.current
    val menu = LocalMainSourcePlaylistSongMenu.current
    val quality = LocalPlayerQualityMenu.current
    val touch = remember { PlayerTouchFrameRate() }
    val idle by rememberUpdatedState({
        enabled && visible && window.isWindowFocused && !touch.pressed &&
            motion.phase == MotionPhase.SHOWN && motion.wantsOpen &&
            !requested && !lyrics.dragging && lyrics.lyricsProgress == 0f && lyrics.headerProgress == 0f &&
            controls.progress == 0f && !queue.visible && navigation?.pages?.isNotEmpty() != true &&
            menu?.expanded != true && quality?.expanded != true
    })
    DisposableEffect(policy, touch) {
        policy.registerIdle(touch) { idle() }
        onDispose { policy.remove(touch) }
    }
    return pointerInput(touch) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed }) touch.pressed = true
                    else {
                        // 松手先让子控件提交点击或 seek，再撤销触摸提帧，避免交接空档。
                        awaitPointerEvent(PointerEventPass.Final)
                        touch.pressed = false
                    }
                }
            }
        } finally { touch.pressed = false }
    }
}
