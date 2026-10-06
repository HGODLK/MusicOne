package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class QqTicketRequestPacerTest {
    @Test
    fun 首次请求立即执行后续请求等待固定间隔() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val pacer = QqTicketRequestPacer(
            intervalMs = 10_000L,
            clockMs = { now },
            waitMs = { duration -> waits += duration; now += duration },
        )

        pacer.awaitTurn()
        now += 2_500L
        pacer.awaitTurn()

        assertEquals(listOf(7_500L), waits)
    }

    @Test
    fun 请求处理耗时超过间隔时不额外等待() = runBlocking {
        var now = 5_000L
        val waits = mutableListOf<Long>()
        val pacer = QqTicketRequestPacer(
            intervalMs = 10_000L,
            clockMs = { now },
            waitMs = { duration -> waits += duration; now += duration },
        )

        pacer.awaitTurn()
        now += 12_000L
        pacer.awaitTurn()

        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun 取消排队不会占用下一次请求的时隙() = runBlocking {
        var now = 1_000L
        var suspendWait = true
        val waiting = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val pacer = QqTicketRequestPacer(clockMs = { now }, waitMs = { duration ->
            waits += duration
            if (suspendWait) {
                waiting.complete(Unit)
                awaitCancellation()
            }
            now += duration
        })
        pacer.awaitTurn()
        val queued = launch { pacer.awaitTurn() }
        waiting.await()
        queued.cancelAndJoin()
        suspendWait = false
        pacer.awaitTurn()
        assertEquals(listOf(10_000L, 10_000L), waits)
    }
}
