package com.musicone

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** ViewModel 只绑定后台播放运行时，页面销毁时不会打断正在进行的切歌或解析。 */
class MusicOneViewModel(application: Application) : AndroidViewModel(application) {
    val playback = MusicPlaybackRuntimeOwner.acquire(application, this)
    private val connection = SystemPlaybackConnection(application)
    private val requestConnection = connection::ensureConnected

    init {
        playback.serviceConnectionRequester = requestConnection
        connection.ensureConnected()
    }

    override fun onCleared() {
        if (playback.serviceConnectionRequester === requestConnection) playback.serviceConnectionRequester = null
        connection.release()
        MusicPlaybackRuntimeOwner.release(this)
    }
}
