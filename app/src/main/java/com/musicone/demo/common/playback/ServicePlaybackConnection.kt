package com.musicone.demo

import androidx.media3.common.Player

/** 播放业务直接连接服务播放器，不让服务绑定自己的 MediaController。 */
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
            // 先登记操作，避免同步重连完成时漏掉本次播放请求。
            callbacks += block
            requestConnection()
        }
    }

    fun release() { closed = true; callbacks.clear(); player = null }
}
