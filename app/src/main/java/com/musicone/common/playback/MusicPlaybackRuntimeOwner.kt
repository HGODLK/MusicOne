package com.musicone

import android.app.Application

/** 管理全局唯一的播放运行时实例，让后台服务和前端页面共享同一份队列和播放状态。 */
internal object MusicPlaybackRuntimeOwner {
    private var lease: PlaybackRuntimeLease<MusicPlaybackRuntime>? = null

    fun acquire(application: Application, owner: Any): MusicPlaybackRuntime {
        val current = lease ?: PlaybackRuntimeLease(
            create = { MusicPlaybackRuntime(application) },
            dispose = { it.release() },
        ).also { lease = it }
        return current.acquire(owner)
    }

    fun release(owner: Any) { lease?.release(owner) }
}
