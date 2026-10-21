package com.musicone.demo

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** 划掉系统控件后允许按页面前台状态或用户操作重新绑定，不在后台强行复活。 */
internal class SystemPlaybackConnection(context: Context) {
    private val context = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(context)
    private var closed = false
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    fun ensureConnected() {
        if (closed || controller?.isConnected == true || future?.isDone == false) return
        future?.let(MediaController::releaseFuture)
        controller = null
        val attempt = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, MusicPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@SystemPlaybackConnection.controller === controller) {
                        this@SystemPlaybackConnection.controller = null
                        // 等用户返回应用或再次操作，不因主动划掉通知立即重启服务。
                    }
                }
            }).buildAsync()
        future = attempt
        attempt.addListener({
            if (!closed && future === attempt) {
                controller = runCatching { attempt.get() }.getOrNull()?.takeIf { it.isConnected }
                if (controller == null) {
                    future = null
                    MediaController.releaseFuture(attempt)
                }
            }
        }, executor)
    }

    fun release() {
        closed = true
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
    }
}
