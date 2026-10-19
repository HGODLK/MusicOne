package com.musicone.demo

import android.util.Log

/** 仅记录来源、歌曲身份和阶段，不输出播放票据、账号凭证或异常正文。 */
internal object PlaybackPreloadDiagnostics {
    fun stage(name: String, track: MusicTrack, errorType: String? = null) {
        Log.i("MusicPlaybackPreload", "阶段=$name 来源=${track.source} 歌曲=${track.id} 异常=${errorType ?: "无"}")
    }
}
