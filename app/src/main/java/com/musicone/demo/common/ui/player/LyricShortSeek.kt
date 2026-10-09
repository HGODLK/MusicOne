package com.musicone.demo

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 一次短距点击只交给一个滚动任务，播放回调等它完成后再恢复跟随。 */
internal class LyricShortSeek {
    var active by mutableStateOf(false)
        private set
    private var revision = 0L
    private var job: Job? = null

    fun start(scope: CoroutineScope, pane: LyricWindowPane, target: Int,
        onFirstFrame: () -> Unit = {}): Job = launchScroll(scope, {
        pane.playbackStep.reset()
        val item = pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (item == null) {
            pane.listState.animateScrollToItem(target)
        } else {
            val travel = lyricVisibleSeekTravel(item.offset)
            pane.listState.animateScrollBy(travel, lyricPlaybackMotionSpec(travel, LyricPlaybackMotionPurpose.SEEK))
        }
        pane.playbackStep.consume(target, enabled = true)
    }, onFirstFrame)

    internal fun launchScroll(scope: CoroutineScope, scroll: suspend () -> Unit,
        onFirstFrame: () -> Unit = {}): Job {
        val request = ++revision
        job?.cancel()
        active = true
        var published = false
        return scope.launch {
            coroutineScope {
                launch { scroll() }
                withFrameNanos { }
                onFirstFrame()
                published = true
            }
        }.also { operation ->
            job = operation
            operation.invokeOnCompletion {
                // 连续点击也可能在协程启动前取消，仍须释放旧请求的就绪等待。
                if (!published) onFirstFrame()
                if (revision == request) active = false
            }
        }
    }

    fun cancel() {
        revision++
        job?.cancel()
        active = false
    }
}
