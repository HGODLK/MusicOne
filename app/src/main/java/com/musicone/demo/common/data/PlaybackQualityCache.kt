package com.musicone.demo

import android.content.Context
import org.json.JSONObject

/**
 * 按音源、歌曲、账号和会员状态记住最高可用音质。
 * 只存档位，不存播放地址或票据；地址仍由预加载票据和平台重新签发。
 */
internal class PlaybackQualityCache(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("playback_quality_cache", Context.MODE_PRIVATE)
    private val platforms = PlatformPreferences(context)
    private val biliSession = BiliSessionStore(context)

    fun read(track: MusicTrack): AudioQuality? = synchronized(this) {
        val raw = preferences.getString(key(track), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        runCatching { AudioQuality.valueOf(json.optString("quality")) }.getOrNull()
    }

    fun write(track: MusicTrack, quality: AudioQuality) {
        if (track.source == MusicSource.LOCAL) return
        if (quality == AudioQuality.DOLBY && !DolbyPlaybackPolicy.permits(quality)) return
        val value = JSONObject()
            .put("quality", quality.name)
            .put("updated", System.currentTimeMillis())
            .toString()
        synchronized(this) {
            preferences.edit().putString(key(track), value).apply()
            pruneLocked()
        }
    }

    /** 只在已有更低缓存时升级；用于“更高档超时、本次暂用低档”的场景。 */
    fun upgrade(track: MusicTrack, quality: AudioQuality) {
        val existing = read(track) ?: return
        if (quality.ordinal > existing.ordinal) write(track, quality)
    }

    fun clear() {
        synchronized(this) { preferences.edit().clear().apply() }
    }

    private fun key(track: MusicTrack): String = playbackQualityCacheKey(
        track.source,
        track.id,
        namespace(track.source),
        vip(track.source),
    )

    private fun namespace(source: MusicSource): String = when (source) {
        MusicSource.BILIBILI -> "BILIBILI:${biliSession.read()?.mid ?: "guest"}"
        MusicSource.LOCAL -> "LOCAL"
        else -> platforms.readSession(source).cacheNamespace()
    }

    private fun vip(source: MusicSource): Boolean =
        source != MusicSource.BILIBILI && source != MusicSource.LOCAL &&
            platforms.readSession(source).account?.hasVipAccess == true

    private fun pruneLocked() {
        val all = preferences.all
        // 留出余量再批量裁剪，避免每次写入都排序整张表。
        if (all.size <= PLAYBACK_QUALITY_CACHE_LIMIT + 200) return
        val remove = all.entries
            .mapNotNull { (key, value) ->
                val updated = runCatching {
                    JSONObject(value as String).optLong("updated", 0L)
                }.getOrDefault(0L)
                key to updated
            }
            .sortedBy { it.second }
            .take(all.size - PLAYBACK_QUALITY_CACHE_LIMIT)
            .map { it.first }
        preferences.edit().also { edit -> remove.forEach(edit::remove) }.apply()
    }
}

/** 键只含身份，不含首选档；偏好变化不应丢失已探测结果。 */
internal fun playbackQualityCacheKey(
    source: MusicSource,
    id: String,
    namespace: String,
    vip: Boolean,
): String = "${source.name}|$id|$namespace|${if (vip) "vip" else "plain"}"
