package com.musicone.sourceintegration

/** 只用于外部播放器的同步输入入口；失败交给原有区域错误面板。 */
internal class SourcePlayerInputBoundary(private val onFailure: (Throwable) -> Unit) {
    fun <T> call(fallback: T, block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        onFailure(failure)
        fallback
    }
}
