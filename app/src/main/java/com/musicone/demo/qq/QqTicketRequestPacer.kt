package com.musicone.demo

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只约束批量缓存换票；取消等待不会预占后续时隙。 */
internal class QqTicketRequestPacer(
    private val intervalMs: Long = QQ_TICKET_REQUEST_INTERVAL_MS,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var nextRequestAtMs = 0L

    suspend fun awaitTurn() = lock.withLock {
        val wait = (nextRequestAtMs - clockMs()).coerceAtLeast(0L)
        if (wait > 0L) waitMs(wait)
        currentCoroutineContext().ensureActive()
        nextRequestAtMs = clockMs() + intervalMs.coerceAtLeast(0L)
    }
}

internal const val QQ_TICKET_REQUEST_INTERVAL_MS = 10_000L
