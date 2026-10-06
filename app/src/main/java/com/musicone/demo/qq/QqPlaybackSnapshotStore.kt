package com.musicone.demo

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal data class QqPlaybackSnapshot(
    val queue: List<MusicTrack>,
    val currentTrackId: String,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val qqRadioActive: Boolean = false,
    val originalQueueIds: List<String> = emptyList(),
)

internal fun MusicOneUiState.toQqPlaybackSnapshot(positionMs: Long): QqPlaybackSnapshot? {
    val current = currentTrack?.takeIf { it.source == MusicSource.QQ } ?: return null
    val qqQueue = queue.filter { it.source == MusicSource.QQ }.toMutableList()
    if (qqQueue.none { it.id == current.id }) qqQueue.add(0, current)
    return QqPlaybackSnapshot(
        queue = qqQueue.map(MusicTrack::forQqPlaybackSnapshot),
        currentTrackId = current.id,
        positionMs = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L)),
        shuffle = shuffle,
        repeatMode = repeatMode,
        qqRadioActive = qqRadioActive,
        originalQueueIds = queueOrder.originalIds,
    )
}

internal fun MusicOneUiState.withQqPlaybackSnapshot(snapshot: QqPlaybackSnapshot?): MusicOneUiState {
    val queue = snapshot?.queue.orEmpty().filter { it.source == MusicSource.QQ }
    val current = queue.firstOrNull { it.id == snapshot?.currentTrackId } ?: queue.firstOrNull()
    return copy(
        currentTrack = current,
        queue = queue,
        queueOrder = PlaybackQueueOrder(snapshot?.originalQueueIds.orEmpty()).reconcile(queue),
        isPlaying = false,
        playerExpanded = false,
        shuffle = snapshot?.shuffle ?: false,
        repeatMode = snapshot?.repeatMode ?: RepeatMode.ALL,
        qqRadioActive = snapshot?.qqRadioActive == true,
        activeQuality = null,
        availableQualities = emptyList(),
        qualityLoading = false,
        qualityChanging = false,
        lyricLoadState = if (current == null) LyricLoadState.UNAVAILABLE else LyricLoadState.LOADING,
        playbackMessage = null,
    )
}

internal fun MusicTrack.forQqPlaybackSnapshot(): MusicTrack = if (previewUrl.isEmpty() && lyrics.isEmpty()) this else copy(
    previewUrl = "",
    lyrics = emptyList(),
)

internal class QqPlaybackSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val encoding = QqPlaybackSnapshotEncoding()

    fun read(): QqPlaybackSnapshot? = preferences.getString(KEY_SNAPSHOT, null)
        ?.let { raw -> runCatching { JSONObject(raw).toQqPlaybackSnapshot() }.getOrNull() }
        ?.let { it.copy(positionMs = preferences.getLong(KEY_POSITION, it.positionMs)) }

    fun save(snapshot: QqPlaybackSnapshot) {
        val content = encoding.contentIfChanged(snapshot)
        preferences.edit {
            content?.let { putString(KEY_SNAPSHOT, it) }
            putLong(KEY_POSITION, snapshot.positionMs)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "qq_playback_snapshot"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_POSITION = "position_ms"
    }
}

/** 连续 seek 只保存进度，歌曲或队列变化时才重新编码完整快照。 */
internal class QqPlaybackSnapshotEncoding {
    private var lastContent: QqPlaybackSnapshot? = null

    fun contentIfChanged(snapshot: QqPlaybackSnapshot): String? {
        val content = snapshot.copy(positionMs = 0L)
        if (content == lastContent) return null
        val encoded = content.toJson().toString()
        lastContent = content
        return encoded
    }
}

private fun QqPlaybackSnapshot.toJson(): JSONObject = JSONObject()
    .put("version", QQ_PLAYBACK_SNAPSHOT_VERSION)
    .put("currentTrackId", currentTrackId)
    .put("positionMs", positionMs)
    .put("shuffle", shuffle)
    .put("repeatMode", repeatMode.name)
    .put("qqRadioActive", qqRadioActive)
    .put("radioProtocolVersion", 1)
    .put("originalQueueIds", JSONArray(originalQueueIds))
    .put("queue", JSONArray().apply { queue.forEach { put(it.toQqStoredTrackJson()) } })

internal fun JSONObject.toQqPlaybackSnapshot(): QqPlaybackSnapshot? {
    if (optInt("version", -1) != QQ_PLAYBACK_SNAPSHOT_VERSION) return null
    // 旧版未指定电台编号，不能把错误电台的队列恢复为猜你喜欢。
    if (optBoolean("qqRadioActive") && optInt("radioProtocolVersion") != 1) return null
    val values = optJSONArray("queue") ?: return null
    val queue = buildList {
        for (index in 0 until values.length()) {
            values.optJSONObject(index)?.toQqStoredTrack()?.let(::add)
        }
    }
    if (queue.isEmpty()) return null
    return QqPlaybackSnapshot(
        queue = queue,
        currentTrackId = optString("currentTrackId"),
        positionMs = optLong("positionMs", 0L).coerceAtLeast(0L),
        shuffle = optBoolean("shuffle", false),
        repeatMode = RepeatMode.entries.firstOrNull { it.name == optString("repeatMode") } ?: RepeatMode.ALL,
        qqRadioActive = optBoolean("qqRadioActive", false),
        originalQueueIds = optJSONArray("originalQueueIds")?.let { ids ->
            List(ids.length()) { ids.optString(it) }.filter(String::isNotBlank)
        }.orEmpty(),
    )
}

internal fun MusicTrack.toQqStoredTrackJson(): JSONObject = JSONObject()
    .put("source", source.name)
    .put("id", id)
    .put("title", title)
    .put("artists", artists)
    .put("album", album)
    .put("durationMs", durationMs)
    .put("artworkStart", artworkStart)
    .put("artworkEnd", artworkEnd)
    .put("artworkMark", artworkMark)
    .put("catalogId", catalogId)
    .put("mediaId", mediaId)
    .put("songMid", songMid)
    .put("albumMid", albumMid)
    .put("artistRefs", JSONArray().apply {
        artistRefs.forEach { put(JSONObject().put("mid", it.mid).put("name", it.name)) }
    })
    .put("providerType", providerType)
    .put("trialAvailable", trialAvailable)
    .put("playable", playable)
    .apply {
        artworkUrl?.let { put("artworkUrl", it) }
        accessBadge?.let { put("accessBadge", it.name) }
        qqAccess?.let { put("qqAccess", it.toJson()) }
        unavailableReason?.let { put("unavailableReason", it) }
        put("qualityIds", JSONObject().apply {
            this@toQqStoredTrackJson.qualityIds.forEach { (quality, id) -> put(quality.name, id) }
        })
    }

internal fun JSONObject.toQqStoredTrack(): MusicTrack? = runCatching {
    val storedSource = MusicSource.entries.firstOrNull { it.name == optString("source") } ?: MusicSource.QQ
    val id = optString("id").takeIf { it.startsWith("${storedSource.name.lowercase()}-") } ?: return null
    val qualityValues = optJSONObject("qualityIds")
    val qualityIds = buildMap {
        AudioQuality.entries.forEach { quality ->
            qualityValues?.optString(quality.name)?.takeIf(String::isNotBlank)?.let { put(quality, it) }
        }
    }
    MusicTrack(
        id = id,
        source = storedSource,
        title = optString("title").ifBlank { "未知歌曲" },
        artists = optString("artists").ifBlank { "未知歌手" },
        album = optString("album"),
        durationMs = optLong("durationMs", 0L).coerceAtLeast(0L),
        artworkStart = optLong("artworkStart", 0L),
        artworkEnd = optLong("artworkEnd", 0L),
        artworkMark = optString("artworkMark"),
        previewUrl = "",
        artworkUrl = optionalString("artworkUrl"),
        catalogId = optString("catalogId"),
        mediaId = optString("mediaId"),
        songMid = optString("songMid"),
        albumMid = optString("albumMid"),
        artistRefs = optJSONArray("artistRefs")?.searchObjects().orEmpty().map {
            MusicArtist(it.optString("mid"), it.optString("name"))
        }.filter { it.mid.isNotBlank() && it.name.isNotBlank() },
        qqAccess = optJSONObject("qqAccess")?.toQqTrackAccessInfo(),
        providerType = optInt("providerType", 0),
        qualityIds = qualityIds,
        accessBadge = optionalString("accessBadge")?.let { name ->
            MusicAccessBadge.entries.firstOrNull { it.name == name }
        },
        trialAvailable = optBoolean("trialAvailable", false),
        playable = optBoolean("playable", true),
        unavailableReason = optionalString("unavailableReason"),
    )
}.getOrNull()

private fun JSONObject.optionalString(name: String): String? =
    takeIf { has(name) && !isNull(name) }?.optString(name)?.takeIf(String::isNotBlank)

private const val QQ_PLAYBACK_SNAPSHOT_VERSION = 1
