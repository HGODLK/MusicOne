package com.musicone.demo

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

internal class SystemPlaybackConnection(context: Context) {
    private val callbacks = mutableListOf<(Player) -> Unit>()
    private var closed = false
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, MusicPlaybackService::class.java)),
    ).buildAsync()

    var player: Player? = null
        private set

    init {
        controllerFuture.addListener(
            {
                val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
                if (closed) return@addListener
                player = controller
                callbacks.toList().also { callbacks.clear() }.forEach { it(controller) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun whenReady(block: (Player) -> Unit) {
        val readyPlayer = player
        if (readyPlayer != null) block(readyPlayer)
        else if (!closed) callbacks += block
    }

    fun release() {
        closed = true
        callbacks.clear()
        player = null
        MediaController.releaseFuture(controllerFuture)
    }
}
