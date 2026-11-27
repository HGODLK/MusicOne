package com.musicone

import kotlinx.coroutines.CoroutineScope

/** 切歌歌词窗口准备超时后的兜底：解除隐藏并从当前可见的旧窗口直接渐变切换到目标窗口。 */
internal suspend fun recoverLyricWindow(target: LyricWindow, sources: List<LyricWindow>,
    motion: LyricWindowMotion, held: LyricHeldTrackTransition, scope: CoroutineScope,
    epoch: LyricDrawEpoch?, travel: Float) {
    val visible = sources.distinct().filter { kotlin.math.abs(motion.offset.value + it.slot) < 1f }
    val starts = visible.map { source ->
        source.freezeDisplayedContent?.invoke()
        if (source.frozenTrackTranslation == null)
            source.frozenTrackTranslation = travel * (motion.offset.value + source.slot)
        source.cleanup?.cancel()
        LyricHeldBlend(source, held.slidingWeight(source) ?: 1f)
    }
    target.recoverDisplay(epoch)
    held.request(visible.lastOrNull(), target, scope, gesturePreparation = false,
        initialSources = starts, drawEpoch = epoch, waitForReady = false)
    held.clearSlides()
    motion.show(target.slot)
}
