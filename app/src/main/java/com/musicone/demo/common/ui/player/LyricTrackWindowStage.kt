package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/** 切歌与寻焦共用窗口角色；绘制读取最新事务，不等待低帧率 UI 重组。 */
internal fun lyricTrackWindowStageRole(
    window: LyricWindow,
    current: LyricWindow?,
    outgoing: List<LyricWindow>,
    held: LyricHeldTrackTransition,
): LyricWindowStageRole = when {
    held.active && held.layers.none { it.window === window } -> LyricWindowStageRole.DETACHED
    window === current -> LyricWindowStageRole.CURRENT
    held.active || outgoing.any { it === window } -> LyricWindowStageRole.OUTGOING
    else -> LyricWindowStageRole.DETACHED
}

/** 图层和文字边界使用相同的即时位移，准备窗口仍在原位静默录制。 */
internal fun lyricTrackWindowTranslation(
    role: LyricWindowStageRole,
    preparing: Boolean,
    held: Boolean,
    travelPx: Float,
    offset: Float,
    slot: Float,
): Float = if (role == LyricWindowStageRole.DETACHED || held ||
    (preparing && role == LyricWindowStageRole.CURRENT)) 0f else travelPx * (offset + slot)

@Composable
internal fun Modifier.lyricTrackWindowStage(
    motion: LyricWindowMotion,
    held: LyricHeldTrackTransition,
    window: LyricWindow,
    role: () -> LyricWindowStageRole,
    preparing: () -> Boolean,
    heldTrackChange: () -> Boolean,
    travelPx: () -> Float,
): Modifier {
    val latestRole = rememberUpdatedState(role)
    val latestPreparing = rememberUpdatedState(preparing)
    val latestHeldTrackChange = rememberUpdatedState(heldTrackChange)
    val latestTravel = rememberUpdatedState(travelPx)
    val layer = rememberGraphicsLayer()
    val recorded = remember { booleanArrayOf(false) }
    return this.graphicsLayer {
        val currentRole = latestRole.value()
        translationY = lyricTrackWindowTranslation(currentRole, latestPreparing.value(), held.active,
            latestTravel.value(), motion.offset.value, window.slot)
        alpha = if (currentRole == LyricWindowStageRole.DETACHED) 0f else 1f
        if (currentRole == LyricWindowStageRole.CURRENT) {
            lyricTracePosition("MusicOne:track-window-y") { translationY }
        }
    }.drawWithContent {
        when (latestRole.value()) {
            LyricWindowStageRole.DETACHED -> Unit
            LyricWindowStageRole.OUTGOING -> {
                // 退场只记录一次，后续位移直接复用图层。
                if (!recorded[0]) {
                    layer.record { this@drawWithContent.drawContent() }
                    recorded[0] = true
                }
                drawLayer(layer)
            }
            LyricWindowStageRole.CURRENT -> {
                val recordHeldSource = latestHeldTrackChange.value() || held.active
                recorded[0] = recordHeldSource
                if (recordHeldSource) {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                } else if (latestPreparing.value()) {
                    layer.record { this@drawWithContent.drawContent() }
                } else drawContent()
            }
        }
    }
}
