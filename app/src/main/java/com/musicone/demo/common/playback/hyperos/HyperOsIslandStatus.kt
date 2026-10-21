package com.musicone.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HyperOsIslandStatus(
    val message: String = "未启用",
    val submitted: Long = 0,
    val registered: Boolean? = null,
) {
    val text: String get() = if (submitted == 0L) message else
        "$message · 更新 $submitted 次 · " + when (registered) {
            true -> "系统已登记"
            false -> "系统尚未登记本轮参数"
            null -> "等待系统登记"
        }
}

/** 只记录本应用发布进度，不把通知已登记当作系统已经展示歌词。 */
internal object HyperOsIslandDiagnostics {
    private val mutable = MutableStateFlow(HyperOsIslandStatus())
    val state = mutable.asStateFlow()

    fun waiting(message: String) { mutable.value = HyperOsIslandStatus(message) }
    fun queued() {
        if (mutable.value.submitted == 0L) waiting("准备中：等待匹配的媒体通知")
    }
    fun submitted(standard: Boolean) {
        val previous = mutable.value
        mutable.value = previous.copy(message = if (standard) "已提交：标准模板" else "已提交：自定义卡片",
            submitted = previous.submitted + 1L)
    }
    fun registered(value: Boolean) {
        if (mutable.value.submitted > 0L) mutable.value = mutable.value.copy(registered = value)
    }
    fun failed(reason: String) { waiting("提交失败：$reason") }
}
