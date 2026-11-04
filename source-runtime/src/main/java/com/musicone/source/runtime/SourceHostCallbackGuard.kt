package com.musicone.source.runtime

import kotlin.coroutines.CoroutineContext

/** 同步宿主边界携带的租约；异步接收者须保存它，并在真正执行动作前重新检查。 */
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
