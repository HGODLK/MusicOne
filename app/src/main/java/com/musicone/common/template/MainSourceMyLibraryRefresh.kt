package com.musicone

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 复用QQ已有可见性门闩，合并重新可见与回前台，不在隐藏页请求资料库。 */
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
