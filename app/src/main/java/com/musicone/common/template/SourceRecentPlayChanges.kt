package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 包成功上报播放记录后通知当前模板刷新，不依赖具体平台实现。 */
internal object SourceRecentPlayChanges {
    private val value = MutableStateFlow(0L)
    val revision = value.asStateFlow()
    fun publish() { value.update { it + 1 } }
}
