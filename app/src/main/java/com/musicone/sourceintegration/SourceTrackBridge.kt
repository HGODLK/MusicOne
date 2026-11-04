package com.musicone.sourceintegration

import com.musicone.*
import com.musicone.source.sdk.*
import org.json.JSONObject

/** 宿主公共模型与公开 SDK 的转换，不解释音源私有协议资料。 */
internal fun MusicTrack.toSourceTrack(accountId: String? = sourceAccountId): SourceTrackDetails {
    val sourceData = providerData - SOURCE_ORIGINAL_STABLE_ID
    // 宿主载荷键另经 payload 交付，不放进 opaqueData，避免音源回传后层层嵌套。
    val opaque = sourceData - HOST_SOURCE_PAYLOAD_KEYS + mapOf(
        "catalogId" to catalogId, "mediaId" to mediaId, "providerType" to providerType.toString(),
        "qualityIds" to JSONObject(qualityIds.mapKeys { it.key.qualityId }).toString(),
    )
    return SourceTrackDetails(
        track = SourceTrack(TrackIdentity(source.id, accountId, remoteId()), title,
            artistRefs.map { it.name }.ifEmpty { listOf(artists) }, artworkUrl, opaque),
        stableId = providerData[SOURCE_ORIGINAL_STABLE_ID] ?: id, album = album, durationMs = durationMs,
        artwork = SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark),
        artists = artistRefs.map { it.sourceReference() },
        albumRef = sourceAlbumReference(),
        access = TrackAccessInfo(when (accessBadge) {
            MusicAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP
            MusicAccessBadge.PAID -> TrackAccessBadge.PURCHASE
            null -> null
        }, trialAvailable, playable, unavailableReason, displayBadge),
        qualities = audioQualities ?: qualityIds.keys.map { SourceAudioQuality(it.qualityId, it.label) },
        lyrics = lyrics.takeIf(List<TimedLyric>::isNotEmpty)?.toSourceLyrics(),
        localUri = localUri, artistsText = artists, previewUrl = previewUrl, payload = sourceData.sourcePayload(), identityAliases = identityAliases,
    )
}

internal fun SourceTrackDetails.toMusicTrack(): MusicTrack {
    val data = track.opaqueData
    val qualityValues = data["qualityIds"]?.let { runCatching { JSONObject(it) }.getOrNull() }
    val qualityIds = qualityValues?.let { values -> values.keys().asSequence().mapNotNull { id ->
        val quality = sourceAudioQuality(id, MusicSource.fromId(track.identity.sourceId))
        values.optString(id).takeIf(String::isNotBlank)?.let { value -> quality?.let { it to value } }
    }.toMap() }.orEmpty()
    return MusicTrack(
        id = sourceStorageId(track.identity.sourceId, track.identity.accountId, stableId), source = MusicSource.fromId(track.identity.sourceId), title = track.title,
        artists = artistsText, album = album, durationMs = durationMs,
        artworkStart = artwork.startArgb, artworkEnd = artwork.endArgb, artworkMark = artwork.mark,
        previewUrl = previewUrl, artworkUrl = artwork.url ?: track.artworkUrl,
        catalogId = data["catalogId"].orEmpty(), mediaId = data["mediaId"].orEmpty(),
        providerType = data["providerType"]?.toIntOrNull() ?: 0, qualityIds = qualityIds,
        accessBadge = when (access.badge) {
            TrackAccessBadge.MEMBERSHIP -> MusicAccessBadge.VIP
            TrackAccessBadge.PURCHASE -> MusicAccessBadge.PAID
            null -> null
        }, trialAvailable = access.trialAvailable, playable = access.playable, unavailableReason = access.reason,
        lyrics = lyrics?.toTimedLyrics().orEmpty(),
        artistRefs = artists.map { MusicArtist(it.id, it.name, it) }, albumId = albumRef?.id.orEmpty(),
        localUri = localUri, providerData = (data - HOST_SOURCE_PAYLOAD_KEYS).toMutableMap().apply {
            put(SOURCE_ORIGINAL_STABLE_ID, stableId)
            if (payload.format.isNotBlank()) {
                put("sourcePayloadFormat", payload.format)
                put("sourcePayloadVersion", payload.version.toString())
                put("sourcePayload", payload.encoded)
            }
        }, sourceAccountId = track.identity.accountId, remoteKey = track.identity.remoteId, identityAliases = identityAliases,
        displayBadge = access.displayBadge,
        albumReference = albumRef, audioQualities = qualities,
    )
}

internal fun MusicPlaylist.toSourceCollection(accountId: String? = sourceAccountId) = SourceCollection(
    identity = TrackIdentity(source.id, accountId, remoteId()), stableId = providerData[SOURCE_ORIGINAL_STABLE_ID] ?: id, title = title,
    subtitle = subtitle, description = description, count = count,
    artwork = SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark), owned = isOwned,
    accountFavorites = isPlatformFavorites, payload = (providerData - SOURCE_ORIGINAL_STABLE_ID).sourcePayload(),
)

private fun Map<String, String>.sourcePayload(): SourcePayload = get("sourcePayloadFormat")?.takeIf(String::isNotBlank)?.let {
    SourcePayload(it, get("sourcePayloadVersion")?.toIntOrNull() ?: 1, get("sourcePayload").orEmpty())
} ?: SourcePayload("musicone.provider-data", 1, JSONObject(this).toString())

internal fun SourceCollection.toMusicPlaylist(tracks: List<MusicTrack> = emptyList()) = MusicPlaylist(
    id = sourceStorageId(identity.sourceId, identity.accountId, stableId), source = MusicSource.fromId(identity.sourceId), title = title, subtitle = subtitle,
    description = description, count = count ?: tracks.size,
    artworkStart = artwork.startArgb, artworkEnd = artwork.endArgb, artworkMark = artwork.mark,
    tracks = tracks, artworkUrl = artwork.url, isOwned = owned, isPlatformFavorites = accountFavorites,
    providerData = (if (payload.format == "musicone.provider-data") runCatching {
        val value = JSONObject(payload.encoded)
        value.keys().asSequence().associateWith { value.optString(it) }
    }.getOrDefault(emptyMap()) else mapOf("sourcePayloadFormat" to payload.format,
        "sourcePayloadVersion" to payload.version.toString(), "sourcePayload" to payload.encoded)) + (SOURCE_ORIGINAL_STABLE_ID to stableId),
    sourceAccountId = identity.accountId, remoteKey = identity.remoteId,
)

internal fun List<TimedLyric>.toSourceLyrics() = SourceLyrics(map { line ->
    SourceLyricLine(line.timeMs, line.text, line.words.map { SourceLyricWord(it.startMs, it.endMs - it.startMs, it.text) },
        line.translation, parserVersion = line.parserVersion)
})

internal fun SourceLyrics.toTimedLyrics(): List<TimedLyric> = lines.map { line ->
    TimedLyric(line.startMs, line.text, line.translation,
        line.words.map { LyricWord(it.startMs, it.startMs + it.durationMs, it.text) }, line.parserVersion)
}
