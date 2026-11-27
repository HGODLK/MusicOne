package com.musicone

/** 歌词窗口在跳转过渡中的角色（当前 / 退场 / 已脱离），实时按控制器状态计算位移和透明度。 */
internal enum class LyricWindowStageRole {
    CURRENT, OUTGOING, DETACHED;

    fun translation(preparing: Boolean, offset: Float, travel: Float): Float =
        if (preparing || this == DETACHED) 0f else offset - if (this == OUTGOING) travel else 0f

    fun alpha(preparing: Boolean, offset: Float, travel: Float): Float = when {
        this == DETACHED -> 0f
        preparing -> if (this == OUTGOING) 1f else 0f
        else -> lyricSeekLayerAlpha(offset, travel, outgoing = this == OUTGOING, crossfade = true)
    }
}

internal fun lyricWindowStageRole(paneId: Long, currentId: Long, outgoingId: Long?): LyricWindowStageRole = when (paneId) {
    currentId -> LyricWindowStageRole.CURRENT
    outgoingId -> LyricWindowStageRole.OUTGOING
    else -> LyricWindowStageRole.DETACHED
}

/** 读取窗口当前的纵向位移，供外层图层和行内边缘渐隐共用，避免重组滞后导致坐标错位。 */
internal fun LyricLayerTransitionController.paneTranslation(pane: LyricWindowPane): Float =
    lyricWindowStageRole(pane.id, currentPane.id, outgoingPane?.id).translation(
        state == LyricLayerTransitionState.PREPARING, drawingOffset, seekTravel)
