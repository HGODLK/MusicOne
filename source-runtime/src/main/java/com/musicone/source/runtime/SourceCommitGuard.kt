package com.musicone.source.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 随请求传给宿主事务，保证最终提交和实例失效共用同一把锁。 */
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
