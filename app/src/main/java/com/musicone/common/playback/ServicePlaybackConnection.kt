package com.musicone

import androidx.media3.common.Player

/** 业务层直接连到 Service 里的 Player 实例，不绕一圈再自己绑定 MediaController。 */
internal class ServicePlaybackConnection(private val requestConnection: () -> Unit = {}) {
    private val callbacks = mutableListOf<(Player) -> Unit>()
    private var closed = false
    var player: Player? = null
        private set

    fun attach(value: Player) {
        if (closed) return
        player = value
        callbacks.toList().also { callbacks.clear() }.forEach { it(value) }
    }

    fun detach(value: Player) { if (player === value) player = null }

    fun whenReady(block: (Player) -> Unit) {
        val current = player
        if (current != null) block(current) else if (!closed) {
            // 先把回调加进队列再触发连接，防止同步连上时漏执行这次操作。
            callbacks += block
            requestConnection()
        }
    }

    fun release() { closed = true; callbacks.clear(); player = null }
}
