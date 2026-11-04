package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

/** 准备完成后等待这一窗口实际绘制，取消的旧曲请求不能放行新曲展开。 */
internal class LyricOpeningDrawProof {
    private class Request(val line: Int, val stamp: LyricDisplayStamp?, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private var request by mutableStateOf<Request?>(null)
    var revision by mutableLongStateOf(0L)
        private set

    suspend fun awaitDraw(line: Int, stamp: LyricDisplayStamp? = null) {
        val pending = Request(line, stamp)
        request = pending
        revision++
        try { pending.ready.await() }
        finally { if (request === pending) request = null }
    }

    fun drawn(line: Int, measured: Boolean, targetVisible: Boolean, scrolling: Boolean,
        stamp: LyricDisplayStamp? = null) {
        val pending = request ?: return
        if (pending.line == line && pending.stamp == stamp && measured && targetVisible && !scrolling)
            pending.ready.complete(Unit)
    }
}
