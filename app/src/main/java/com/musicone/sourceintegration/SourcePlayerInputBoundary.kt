package com.musicone.sourceintegration

/** 兜底捕获自建播放页同步输入回调里的异常，出错时切到错误面板不闪退。 */
internal class SourcePlayerInputBoundary(private val onFailure: (Throwable) -> Unit) {
    fun <T> call(fallback: T, block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        onFailure(failure)
        fallback
    }
}
