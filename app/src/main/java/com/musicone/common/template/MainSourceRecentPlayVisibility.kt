package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** 首页和“我的”页共用最近播放刷新门控：页面隐藏期间只记一次待刷新，回到可见时再统一拉。 */
internal class MainSourceRecentPlayRefreshGate {
    private val visibleOwners = mutableSetOf<Any>()
    private var pending = true
    val visible: Boolean get() = visibleOwners.isNotEmpty()

    fun setVisible(owner: Any, visible: Boolean) {
        if (visible) visibleOwners.add(owner) else visibleOwners.remove(owner)
    }

    fun invalidate() { pending = true }

    fun consumePending(): Boolean {
        if (!visible || !pending) return false
        pending = false
        return true
    }
}

@Composable
internal fun ObserveMainSourceRecentPlayVisibility(viewModel: MainSourceRecentPlayViewModel, visible: Boolean) {
    val owner = remember(viewModel) { Any() }
    DisposableEffect(viewModel, owner, visible) {
        viewModel.setVisible(owner, visible)
        onDispose { viewModel.setVisible(owner, false) }
    }
}
