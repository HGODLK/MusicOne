package com.musicone.sourceintegration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class SourceStartupState(val initializing: Boolean = true, val error: String? = null)

/** 音源初始化绑定在应用级作用域，页面销毁只取消等待，不会打断正在跑的安装初始化。 */
internal class SourceStartup(private val scope: CoroutineScope, private val initialize: suspend () -> Unit) {
    private val mutableState = MutableStateFlow(SourceStartupState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                initialize()
                mutableState.value = SourceStartupState(initializing = false)
            } catch (cancelled: CancellationException) {
                mutableState.value = SourceStartupState(false, "音源初始化已中断，请重启应用")
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = SourceStartupState(false, failure.message ?: "音源初始化失败")
            }
        }
    }

    suspend fun await() {
        start()
        state.first { !it.initializing }
    }
}
