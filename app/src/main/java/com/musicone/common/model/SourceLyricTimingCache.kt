package com.musicone

import com.musicone.source.sdk.SourceLyricTiming

/** 只保留最近一曲；曲目或音源实例更换后重新读取，避免状态通知反复转换整曲逐字歌词。 */
internal class SourceLyricTimingCache {
    private var track: MusicTrack? = null
    private var owner: Any? = null
    private var timing: SourceLyricTiming? = null

    @Synchronized
    fun read(track: MusicTrack, owner: Any?, calculate: () -> SourceLyricTiming): SourceLyricTiming {
        if (this.track === track && this.owner === owner) timing?.let { return it }
        return calculate().also {
            this.track = track
            this.owner = owner
            timing = it
        }
    }
}
