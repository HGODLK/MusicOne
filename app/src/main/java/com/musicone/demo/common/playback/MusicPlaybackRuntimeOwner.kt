package com.musicone.demo

import android.app.Application

/** 服务持有的播放运行对象与页面适配器共享队列和请求代次。 */
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
