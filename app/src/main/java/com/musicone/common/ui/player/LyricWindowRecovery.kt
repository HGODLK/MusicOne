package com.musicone

import kotlinx.coroutines.CoroutineScope

/** 超时只撤掉目标的隐藏资格，沿既有互补渐变从最后实际来源交给同一歌词窗口。 */
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
