package com.musicone.demo

/** 角色随控制器事务更新，不依赖 UI 重组是否已追上歌词动画帧。 */
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
