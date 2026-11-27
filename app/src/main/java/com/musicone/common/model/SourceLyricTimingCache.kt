package com.musicone

import com.musicone.source.sdk.SourceLyricTiming

/** 只缓存当前这一首歌的歌词时间配置，切歌或换音源实例时再重算，避免每次状态更新都重转一遍。 */
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
