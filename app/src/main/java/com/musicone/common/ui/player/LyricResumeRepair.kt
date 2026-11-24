package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope

/** 回到前台后等界面稳定再检查，避开恢复首帧和仍在收尾的正常动画。 */
internal const val LyricResumeRepairDelayMs = 600L

/** 每次回到前台加一；首次进入也算一次，检查本身对正常状态不做任何改动。 */
@Composable
internal fun rememberLyricResumeSignal(): Int {
    var signal by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { signal++ }
    return signal
}

/**
 * 回到前台的兜底：歌词应当显示却停在不可见状态（没有窗口、停在旧歌、卡在准备、窗口没落到当前位置）时，
 * 直接把当前歌词摆回原位，不播入场动画。正在运动或渐变交接中不干预，正常状态下什么都不做。
 */
internal suspend fun repairLyricWindowAfterResume(
    target: LyricPresentation?,
    current: LyricWindow?,
    preparing: Boolean,
    outgoing: List<LyricWindow>,
    motion: LyricWindowMotion,
    held: LyricHeldTrackTransition,
    scope: CoroutineScope,
    epoch: LyricDrawEpoch?,
    travel: Float,
    progress: PlaybackProgressSnapshot,
    previewMs: Long?,
    replace: (LyricWindow) -> Unit,
) {
    if (target == null || motion.isRunning || held.active) return
    if (current != null && !preparing && current.presentation.id == target.id &&
        current.presentation.source == target.source) {
        // 同一首：布局迟迟未就绪就改走兜底绘制，窗口没落到当前位置就直接归位。
        if (!current.ready.isCompleted) current.recoverDisplay(epoch)
        if (!motion.settledAt(current.slot)) motion.show(current.slot)
        return
    }
    // 没有窗口、停在旧歌或卡在准备：按当前歌词重建，复用超时恢复的直接显示路径。
    val sources = outgoing + listOfNotNull(current)
    val incoming = LyricWindow(target)
    incoming.playback.update(progress, previewMs, browsing = false)
    replace(incoming)
    LyricDisplayDiagnostics.recovery("回到前台歌词不可见", incoming)
    recoverLyricWindow(incoming, sources, motion, held, scope, epoch, travel)
}
