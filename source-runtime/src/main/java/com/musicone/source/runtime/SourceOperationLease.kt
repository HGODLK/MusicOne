package com.musicone.source.runtime

import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

internal class SourceOperationLease(private val checkActive: () -> Unit) {
    private val commitGate = Any()
    var accountVersion: () -> Any? = { null }
    private val jobs = CopyOnWriteArraySet<kotlinx.coroutines.CompletableJob>()
    fun <T> read(block: () -> T): T { checkActive(); return block().also { checkActive() } }
    suspend fun <T> call(checkAccount: Boolean = true, block: suspend () -> T): T {
        checkActive()
        val inherited = coroutineContext[AccountScope]?.takeIf { it.owner === this }
        val account = if (checkAccount) inherited ?: AccountScope(this, accountVersion()) else null
        fun checkVersion() {
            checkActive()
            if (account != null && account.version != accountVersion()) throw CancellationException("音源账号已变化")
        }
        checkVersion()
        val operation = Job(coroutineContext[Job])
        jobs.add(operation)
        try {
            checkVersion()
            val scope = if (account == null) operation else operation + account
            return withContext(scope + SourceCommitGuard(commitGate, ::checkVersion)) { block().also { checkVersion() } }
        } finally { jobs.remove(operation); operation.complete() }
    }
    fun deferredCheck(): () -> Unit {
        checkActive()
        val account = accountVersion()
        return {
            checkActive()
            if (account != accountVersion()) throw CancellationException("音源账号已变化")
        }
    }
    fun deferredCommitGuard() = SourceCommitGuard(commitGate, deferredCheck())
    fun invalidate(markInactive: () -> Unit) {
        synchronized(commitGate) { markInactive() }
        close()
    }
    fun close() { jobs.forEach { it.cancel() }; jobs.clear() }

    private class AccountScope(val owner: SourceOperationLease, val version: Any?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AccountScope>
    }
}
