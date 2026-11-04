package com.musicone

import android.content.Context
import org.json.JSONObject

/**
 * 按音源、歌曲、账号和会员状态记住最高可用音质。
 * 只存档位，不存播放地址或票据；地址仍由预加载票据和平台重新签发。
 */
internal class PlaybackQualityCache(private val context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("playback_quality_cache", Context.MODE_PRIVATE)

    fun read(track: MusicTrack): AudioQuality? = synchronized(this) {
        val raw = preferences.getString(key(track), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        json.storedQuality()
    }

    fun write(track: MusicTrack, quality: AudioQuality) {
        if (track.source == MusicSource.LOCAL) return
        if (!DolbyPlaybackPolicy.permits(quality)) return
        val value = JSONObject()
            .putStoredQuality(quality)
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
        if (quality.isHigherThan(existing)) write(track, quality)
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

    private fun namespace(source: MusicSource): String = if (source == MusicSource.LOCAL) "LOCAL"
        else com.musicone.sourceintegration.sourceCacheNamespace(context, source)

    private fun vip(source: MusicSource): Boolean? =
        com.musicone.sourceintegration.sourceMembershipState(context, source)

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
    vip: Boolean?,
): String = "${source.name}|$id|$namespace|${when (vip) { true -> "vip"; false -> "plain"; null -> "unknown" }}"
