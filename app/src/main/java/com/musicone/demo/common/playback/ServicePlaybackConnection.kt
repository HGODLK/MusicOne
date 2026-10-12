package com.musicone.demo

import androidx.media3.common.Player

/** 播放业务直接连接服务播放器，不让服务绑定自己的 MediaController。 */
internal class ServicePlaybackConnection {
    private val callbacks = mutableListOf<(Player) -> Unit>()
    var player: Player? = null
        private set

    fun attach(value: Player) {
        player = value
        callbacks.toList().also { callbacks.clear() }.forEach { it(value) }
    }

    fun detach(value: Player) { if (player === value) player = null }

    fun whenReady(block: (Player) -> Unit) {
        val current = player
        if (current != null) block(current) else callbacks += block
    }

    fun release() { callbacks.clear(); player = null }
}
