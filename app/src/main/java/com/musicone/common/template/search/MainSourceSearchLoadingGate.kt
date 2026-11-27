package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** 搜索展开动画期间先拦住网络请求和数据回写，等动画落稳再放行，防止动画掉帧。 */
internal class MainSourceSearchLoadingGate {
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
internal fun MainSourceSearchLoadingGuard(state: MainSourceSearchState, motion: MainSourceSearchMotion,
    model: MainSourceSearchViewModel, obscured: Boolean) {
    val current by rememberUpdatedState(state)
    val covered by rememberUpdatedState(obscured)
    DisposableEffect(model) { onDispose { model.loadingGate.pause() } }
    LaunchedEffect(model, motion) {
        snapshotFlow {
            // 带上 generation 一起监听，防止同一帧关了又开因为 ready 值没变而卡死。
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
