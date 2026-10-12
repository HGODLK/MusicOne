package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** 首页和我的页共用刷新入口；隐藏期间保留一次待刷新，不重复解析最近播放。 */
internal class QqRecentPlayRefreshGate {
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
internal fun ObserveQqRecentPlayVisibility(viewModel: QqRecentPlayViewModel, visible: Boolean) {
    val owner = remember(viewModel) { Any() }
    DisposableEffect(viewModel, owner, visible) {
        viewModel.setVisible(owner, visible)
        onDispose { viewModel.setVisible(owner, false) }
    }
}
