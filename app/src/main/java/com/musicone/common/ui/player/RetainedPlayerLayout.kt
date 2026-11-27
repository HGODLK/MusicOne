package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** 计算手机播放页的布局旋转角度：隐藏预热时按当前握持方向提前测量，开合开始后锁定本轮方向。 */
internal fun retainedPlayerLayoutRotation(activeRotation: Int, upcomingRotation: Int,
    mounted: Boolean, retentionAllowed: Boolean, defaultPhonePlayer: Boolean): Int =
    if (defaultPhonePlayer && retentionAllowed && !mounted) upcomingRotation else activeRotation

@Composable
internal fun RetainedPlayerLayout(motion: PageMotion, presentation: PhonePlayerPresentation?,
    retention: PlayerRetentionState, content: @Composable () -> Unit) {
    val activeRotation = LocalPhonePlayerRotation.current
    val defaultPhonePlayer = presentation != null && !presentation.customPlayer
    // 开合已开始后只跟随本轮公开方向，不因传感器后续变化重排动画中的目标。
    val upcomingRotation = if (defaultPhonePlayer && retention.allowed && !motion.mounted)
        presentation?.nativeOpeningRotation ?: activeRotation else activeRotation
    val rotation = retainedPlayerLayoutRotation(activeRotation, upcomingRotation,
        motion.mounted, retention.allowed, defaultPhonePlayer)
    val layers = presentation?.openingAnchors?.layers?.takeIf { defaultPhonePlayer }
    val owner = remember { Any() }
    DisposableEffect(layers, owner) {
        layers?.attach(owner)
        onDispose { layers?.detach(owner) }
    }
    CompositionLocalProvider(LocalPhonePlayerRotation provides rotation,
        LocalPlayerOpeningLayers provides layers, content = content)
}
