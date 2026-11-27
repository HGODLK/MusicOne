package com.musicone

import android.util.Log

/** 记录预加载各阶段日志（不打印播放地址、账号凭证或敏感报错信息）。 */
internal object PlaybackPreloadDiagnostics {
    fun stage(name: String, track: MusicTrack, errorType: String? = null) {
        Log.i("MusicPlaybackPreload", "阶段=$name 来源=${track.source} 歌曲=${track.id} 异常=${errorType ?: "无"}")
    }
}
