package com.musicone.demo

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** 保留资格在内存告急后锁定关闭，避免回收后马上重新预热。 */
@Stable
internal class PlayerRetentionState(allowed: Boolean) {
    var allowed by mutableStateOf(allowed)
        private set
    var foreground by mutableStateOf(true)
    var warmed by mutableStateOf(false)
        private set

    fun release() { allowed = false; warmed = false }
    fun markWarmed() { if (allowed) warmed = true }
    fun shouldMount(visible: Boolean): Boolean = foreground && (visible || allowed)
    val launchReady: Boolean get() = !allowed || !foreground || warmed
}

internal fun playerRetentionMemorySafe(
    lowRamDevice: Boolean,
    systemLowMemory: Boolean,
    heapMaxBytes: Long,
    heapUsedBytes: Long,
): Boolean = !lowRamDevice && !systemLowMemory &&
    heapMaxBytes - heapUsedBytes >= maxOf(32L * 1024 * 1024, heapMaxBytes / 8)

/** 新版系统不再发送运行中低内存级别，因此同时检查堆余量和系统低内存状态。 */
@Composable
internal fun rememberPlayerRetentionState(): PlayerRetentionState {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    val memory = remember { ActivityManager.MemoryInfo() }
    fun memorySafe(): Boolean {
        manager.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        return playerRetentionMemorySafe(manager.isLowRamDevice, memory.lowMemory,
            runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory())
    }
    val state = remember { PlayerRetentionState(memorySafe()) }
    DisposableEffect(context, lifecycle, state) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { state.release() }
            override fun onTrimMemory(level: Int) {
                // 包括旧系统运行中压力和所有后台级别；只释放额外页面，不干扰播放服务。
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) state.release()
            }
        }
        val observer = LifecycleEventObserver { _, _ ->
            state.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!memorySafe()) state.release()
        }
        context.registerComponentCallbacks(callbacks)
        lifecycle.addObserver(observer)
        onDispose {
            context.unregisterComponentCallbacks(callbacks)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(lifecycle, state) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (state.allowed) {
                if (!memorySafe()) state.release()
                delay(5_000)
            }
        }
    }
    return state
}
