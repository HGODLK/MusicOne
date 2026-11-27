package com.musicone

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 页面重新可见或切回前台时合并触发一次资料库刷新，页面不可见时不发请求。 */
@Composable
internal fun ObserveMainSourceMyLibraryRefresh(identity: Any?, signedIn: Boolean, visible: Boolean,
    onRefresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val gate = remember(identity, lifecycle) { MainSourceRecentPlayRefreshGate() }
    val owner = remember(gate) { Any() }
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(gate, signedIn, visible, lifecycleState) {
        val ready = signedIn && visible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        gate.setVisible(owner, ready)
        if (!ready) gate.invalidate()
        else if (gate.consumePending()) refresh()
    }
    DisposableEffect(gate, owner) { onDispose { gate.setVisible(owner, false) } }
}
