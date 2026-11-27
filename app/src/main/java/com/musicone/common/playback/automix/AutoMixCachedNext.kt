package com.musicone

import android.content.Context
import android.os.SystemClock

/**
 * 断网时自然过渡取下一首：预取器断网即清空任务，已整首离线缓存的歌只能直接从磁盘缓存取。
 * 取档规则与普通切歌的缓存优先一致（OfflineMusicStore.resolve 只返回校验过的整首缓存）。
 * 联网时仍以预取器为准，这里不参与；未命中的歌短时间内不重复扫描缓存索引。
 */
internal class AutoMixCachedNext(context: Context) {
    private val store by lazy { OfflineMusicStore(context.applicationContext) }
    private var miss: Pair<String, Long>? = null

    suspend fun resolve(track: MusicTrack, preferred: AudioQuality): ResolvedPlayback? {
        if (store.hasNetwork()) return null
        val now = SystemClock.elapsedRealtime()
        miss?.let { (id, at) -> if (id == track.id && now - at < MISS_RETRY_MS) return null }
        val resolved = store.resolve(track, preferred, allowOtherQuality = true)
        miss = if (resolved == null) track.id to now else null
        return resolved
    }

    private companion object {
        const val MISS_RETRY_MS = 15_000L
    }
}
