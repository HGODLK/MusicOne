package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** 跨曲窗口保留独立进度，滑动和手势中间态交接共用同一窗口身份。 */
internal class LyricWindow(presentation: LyricPresentation, val slot: Float = 0f) {
    var presentation by mutableStateOf(presentation)
    val playback = LyricWindowPlayback(presentation.id)
    val ready = CompletableDeferred<Unit>()
    val preparationBudget = LyricPreparationBudget()
    var displayFallback by mutableStateOf(false)
        private set
    fun recoverDisplay(epoch: LyricDrawEpoch?) {
        if (displayFallback) return
        displayFallback = true
        epoch?.refresh()
        LyricDisplayDiagnostics.recovery("隐藏目标超时", this)
    }
    var cleanup: Job? = null
    // 仅冻结已成为渐变来源的窗口，外层页面/转向仍由原宿主控制。
    var frozenTrackTranslation by mutableStateOf<Float?>(null)
    var freezeDisplayedContent: (() -> Unit)? = null

    fun canReturnTo(target: LyricPresentation, progress: PlaybackProgressSnapshot, restarting: Boolean): Boolean =
        playback.canReturnTo(progress, restarting) &&
            presentation.source == target.source && presentation.id == target.id

    // 同曲加载重试只换播放轮次，不生成一次跨曲入场；新窗口仍隔离旧轮次的 seek。
    fun refreshPlaybackRound(target: LyricPresentation, progress: PlaybackProgressSnapshot): LyricWindow? =
        if (presentation.source == target.source && presentation.id == target.id && progress.trackId == target.id) {
            LyricWindow(target, slot).also { it.playback.update(progress) }
        } else null
}
