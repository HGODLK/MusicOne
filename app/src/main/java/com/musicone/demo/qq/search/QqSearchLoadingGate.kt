package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** 冷请求和数据回写都让路给页面展开，已开始的 IO 不在每一动画帧重启。 */
internal class QqSearchLoadingGate {
    private val ready = MutableStateFlow(false)
    var generation by mutableIntStateOf(0)
        private set

    fun pause() {
        ready.value = false
        generation++
    }
    fun update(value: Boolean) { ready.value = value }
    suspend fun awaitReady() { ready.first { it } }
}

internal fun searchLoadingReady(opened: Boolean, full: Boolean, obscured: Boolean,
    menuProgress: Float, menuRunning: Boolean, fullProgress: Float, fullMoving: Boolean,
    resultsReady: Boolean): Boolean = opened && !obscured && !menuRunning && !fullMoving &&
    menuProgress == 1f && (if (full) fullProgress == 1f && resultsReady else fullProgress == 0f)

@Composable
internal fun QqSearchLoadingGuard(state: QqSearchState, motion: QqSearchMotion,
    model: QqSearchViewModel, obscured: Boolean) {
    val current by rememberUpdatedState(state)
    val covered by rememberUpdatedState(obscured)
    DisposableEffect(model) { onDispose { model.loadingGate.pause() } }
    LaunchedEffect(model, motion) {
        snapshotFlow {
            // 同一帧关闭后重开也重新核对，不能因就绪布尔值相同而一直暂停。
            model.loadingGate.generation to searchLoadingReady(current.opened, current.full, covered,
                motion.menu.value, motion.menu.isRunning, motion.full.value,
                motion.fullMoving, motion.resultsReady)
        }.collectLatest { (_, ready) ->
            // 先呈现动画终点，再放行请求及已完成请求的内容回写。
            if (ready) withFrameNanos { }
            model.loadingGate.update(ready)
        }
    }
}
