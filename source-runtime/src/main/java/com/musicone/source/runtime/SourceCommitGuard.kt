package com.musicone.source.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 随音源请求传递到宿主事务，最终提交与实例失效使用同一个锁。 */
class SourceCommitGuard internal constructor(
    private val gate: Any,
    private val checkActive: () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    fun check() = checkActive()

    fun <T> commit(block: () -> T): T = synchronized(gate) {
        checkActive()
        block()
    }

    companion object Key : CoroutineContext.Key<SourceCommitGuard>
}
