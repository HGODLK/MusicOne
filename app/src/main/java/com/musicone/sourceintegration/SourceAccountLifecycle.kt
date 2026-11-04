package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.musicone.MusicSource
import kotlinx.coroutines.delay

/** 切换音源、恢复前台与前台停留时按宿主节流检查公开账号，续期逻辑仍由音源执行。 */
@Composable
internal fun SourceAccountLifecycle(source: MusicSource, refreshIfDue: () -> Unit) {
    val latestRefresh = rememberUpdatedState(refreshIfDue)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { latestRefresh.value() }
    LaunchedEffect(source) { latestRefresh.value() }
    // 节拍只读取本地账号状态，是否联网由宿主节流决定；进入后台即停止，不空跑登录请求。
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(ACCOUNT_CHECK_INTERVAL_MS)
                latestRefresh.value()
            }
        }
    }
}

private const val ACCOUNT_CHECK_INTERVAL_MS = 60_000L
