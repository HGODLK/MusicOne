package com.musicone.demo

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
    var cleanup: Job? = null

    fun canReturnTo(target: LyricPresentation, progress: PlaybackProgressSnapshot, restarting: Boolean): Boolean =
        playback.canReturnTo(progress, restarting) &&
            presentation.source == target.source && presentation.id == target.id
}
