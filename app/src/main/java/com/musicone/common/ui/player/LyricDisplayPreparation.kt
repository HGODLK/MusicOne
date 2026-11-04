package com.musicone

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** 每个隐藏目标只取得一次预算；内部等待继承开合／转向剩余时间，不重新计三秒。 */
internal class LyricPreparationBudget(
    private val now: () -> Long = System::nanoTime,
    private val deadline: Long = now() + PHONE_PLAYER_TURN_PREPARATION_MILLIS * 1_000_000L,
) {
    fun remainingMillis(parentDeadline: Long = Long.MAX_VALUE): Long =
        ((minOf(deadline, parentDeadline) - now()).coerceAtLeast(0L) / 1_000_000L)

    suspend fun await(ready: Deferred<Unit>, parentDeadline: Long = Long.MAX_VALUE): Boolean {
        currentCoroutineContext().ensureActive()
        if (ready.isCompleted && !ready.isCancelled) return true
        if (ready.isCancelled) return false
        return withTimeoutOrNull(remainingMillis(parentDeadline)) { ready.await(); true } == true
    }
}

/** 回执匹配实际窗口、歌曲、宿主挂载、视口与方向，不能用旧绘制放行新准备。 */
internal data class LyricDisplayStamp(val pane: LyricWindowPane, val track: String,
    val mount: Int, val viewport: IntSize, val padding: Int, val rotation: Int)

internal fun lyricDisplayStamp(pane: LyricWindowPane, track: String, rotation: Int): LyricDisplayStamp? {
    val info = pane.listState.layoutInfo
    if (!pane.listHost.ready || info.viewportSize.width <= 0 || info.viewportSize.height <= 0) return null
    return LyricDisplayStamp(pane, track, pane.listHost.revision, info.viewportSize, info.beforeContentPadding, rotation)
}

/** 只有显示事务、宿主或视口变化才重录引用，正常播放帧及滚动偏移不进入缓存键。 */
@Composable
internal fun ObserveLyricDisplay(epoch: LyricDrawEpoch?, owner: Any, panes: List<LyricWindowPane?>,
    preparation: List<Any?>) {
    val mounts by remember(panes) {
        derivedStateOf {
            panes.map { pane -> pane?.let {
                val info = it.listState.layoutInfo
                listOf(it.listHost.revision, info.viewportSize, info.beforeContentPadding)
            } }
        }
    }
    SideEffect { epoch?.observe(owner, panes + mounts + preparation) }
    DisposableEffect(epoch, owner) { onDispose { epoch?.remove(owner) } }
}

@Composable
internal fun BindLyricPreparation(controller: LyricLayerTransitionController, epoch: LyricDrawEpoch?,
    turn: PhonePlayerTurnPreparation?, opening: PhoneLyricsMotion?) {
    SideEffect {
        controller.preparationDeadline = {
            minOf(turn?.deadlineNanos ?: Long.MAX_VALUE, opening?.openingDeadlineNanos ?: Long.MAX_VALUE)
        }
        controller.refreshDisplay = { epoch?.refresh() }
    }
}

/** 沿用现有构建诊断开关，仅记录异常阶段与匿名窗口编号，不输出歌词或歌曲资料。 */
internal object LyricDisplayDiagnostics {
    private val reported = LinkedHashSet<Pair<String, Int>>()
    @Synchronized fun recovery(stage: String, window: Any) {
        if (!BuildConfig.MUSICONE_TRACE) return
        val token = System.identityHashCode(window)
        if (!reported.add(stage to token)) return
        if (reported.size > 128) reported.remove(reported.first())
        Log.w("MusicOneLyricDisplay", "阶段=$stage 窗口=$token")
    }
}
