package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** 单个切歌歌词窗口实例，独立持有播放进度与准备状态，在滑动和渐变切歌间保持同一身份。 */
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

    // 同一首歌重新加载或重播时只更新播放轮次，不触发切歌动画。
    /**
     * 同一首重新起播（换音质、重连播放器、恢复失效播放等）只换播放轮次：原窗口原地改绑，
     * 列表与显示状态全部保留。不能新建窗口，新窗口不经准备与就绪流程，会让歌词停在不可见状态。
     */
    fun refreshPlaybackRound(target: LyricPresentation, progress: PlaybackProgressSnapshot): Boolean {
        if (presentation.source != target.source || presentation.id != target.id || progress.trackId != target.id) return false
        presentation = target
        playback.rebind(progress)
        android.util.Log.i("MusicOneLyricDisplay", "同曲换播放轮次，原窗口改绑 轮次=${progress.generation}")
        return true
    }
}
