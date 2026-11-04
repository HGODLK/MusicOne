package com.musicone

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 旧画面覆盖期间暂停退场，交接后从原位置和速度续接；请求取消则由新目标接管。 */
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
