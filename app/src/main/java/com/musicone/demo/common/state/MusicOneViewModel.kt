package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** 页面只绑定服务；清理页面不能取消服务正在执行的切歌和解析。 */
class MusicOneViewModel(application: Application) : AndroidViewModel(application) {
    val playback = MusicPlaybackRuntimeOwner.acquire(application, this)
    private val connection = SystemPlaybackConnection(application)

    override fun onCleared() {
        connection.release()
        MusicPlaybackRuntimeOwner.release(this)
    }
}
