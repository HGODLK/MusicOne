package com.musicone.source.runtime

import kotlin.coroutines.CoroutineContext

/** 跨越宿主边界时附带的租约；异步接收方需保存并在实际执行前再次校验。 */
class SourceHostCallbackGuard private constructor(private val commitGuard: SourceCommitGuard, val sourceId: String?) {
    val context: CoroutineContext get() = commitGuard
    fun isCurrent(): Boolean = runCatching { commitGuard.check(); true }.getOrDefault(false)

    companion object {
        private val current = ThreadLocal<SourceHostCallbackGuard>()

        fun capture(): SourceHostCallbackGuard? = current.get()

        internal fun <T> withGuard(guard: SourceCommitGuard, sourceId: String?, action: () -> T): T {
            val previous = current.get()
            current.set(SourceHostCallbackGuard(guard, sourceId))
            try { return action() } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }
}

internal fun <T> SourceOperationLease.dispatch(sourceId: String? = null, action: () -> T): T {
    val guard = deferredCommitGuard()
    guard.check()
    return SourceHostCallbackGuard.withGuard(guard, sourceId, action)
}
