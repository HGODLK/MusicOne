package com.musicone

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 时钟退场动画：旧画面快照还没撤掉前先暂停，画面揭开后再从原位置和速度继续滑出。 */
internal suspend fun PhoneClockFlight.exitWhenVisible(
    covered: () -> Boolean,
    travel: () -> Float,
    beforeExit: suspend () -> Unit = {},
) = coroutineScope {
    val finished = CompletableDeferred<Unit>()
    val observer = launch {
        snapshotFlow(covered).collectLatest { hiddenBySnapshot ->
            if (!hiddenBySnapshot) {
                beforeExit()
                animateTo(0f, travel())
                finished.complete(Unit)
            }
        }
    }
    try { finished.await() }
    finally { observer.cancelAndJoin() }
}
