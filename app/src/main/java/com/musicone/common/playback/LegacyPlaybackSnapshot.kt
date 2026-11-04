package com.musicone

import android.content.Context
import org.json.JSONObject

internal data class LegacyPlaybackSnapshot(
    val queue: List<MusicTrack>,
    val currentTrackId: String,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val radioActive: Boolean = false,
    val originalQueueIds: List<String> = emptyList(),
)

internal class LegacyPlaybackSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences("qq_playback_snapshot", Context.MODE_PRIVATE)
    fun read(): LegacyPlaybackSnapshot? = preferences.getString("snapshot", null)
        ?.let { raw -> runCatching { JSONObject(raw).toLegacyPlaybackSnapshot() }.getOrNull() }
        ?.let { it.copy(positionMs = preferences.getLong("position_ms", it.positionMs)) }
}

internal fun JSONObject.toLegacyPlaybackSnapshot(): LegacyPlaybackSnapshot? {
    if (optInt("version", -1) != LEGACY_PLAYBACK_SNAPSHOT_VERSION) return null
    // 旧版未指定电台编号，不能把错误电台的队列恢复为猜你喜欢。
    if (optBoolean("qqRadioActive") && optInt("radioProtocolVersion") != 1) return null
    val values = optJSONArray("queue") ?: return null
    val queue = buildList {
        for (index in 0 until values.length()) {
            values.optJSONObject(index)?.toStoredTrack()?.let(::add)
        }
    }
    if (queue.isEmpty()) return null
    return LegacyPlaybackSnapshot(
        queue = queue,
        currentTrackId = optString("currentTrackId"),
        positionMs = optLong("positionMs", 0L).coerceAtLeast(0L),
        shuffle = optBoolean("shuffle", false),
        repeatMode = RepeatMode.entries.firstOrNull { it.name == optString("repeatMode") } ?: RepeatMode.ALL,
        radioActive = optBoolean("qqRadioActive", false),
        originalQueueIds = optJSONArray("originalQueueIds")?.let { ids ->
            List(ids.length()) { ids.optString(it) }.filter(String::isNotBlank)
        }.orEmpty(),
    )
}

private const val LEGACY_PLAYBACK_SNAPSHOT_VERSION = 1
