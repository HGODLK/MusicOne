package com.musicone.source.netease

import com.musicone.source.sdk.*
import org.json.JSONObject

internal fun NeteaseTrack.toDetails(account: String?): SourceTrackDetails {
    val payload = JSONObject().put("id", id).put("remoteId", remoteId()).put("title", title).put("artists", artists)
        .put("album", album).put("durationMs", durationMs).put("artworkStart", artworkStart).put("artworkEnd", artworkEnd)
        .put("artworkMark", artworkMark).put("previewUrl", previewUrl).put("artworkUrl", artworkUrl)
        .put("badge", accessBadge?.name).put("trial", trialAvailable).put("playable", playable)
    return SourceTrackDetails(SourceTrack(TrackIdentity(NETEASE_SOURCE_ID, account, remoteId()), title, listOf(artists), artworkUrl),
        id, album, durationMs, SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark),
        access = TrackAccessInfo(when (accessBadge) { NeteaseAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP; NeteaseAccessBadge.PAID -> TrackAccessBadge.PURCHASE; null -> null },
            trialAvailable, playable, unavailableReason), qualities = NETEASE_AUDIO_QUALITIES.map { SourceAudioQuality(it.name, it.label) },
        payload = SourcePayload("netease.track", 1, payload.toString()), artistsText = artists, previewUrl = previewUrl)
}
internal fun SourceTrackDetails.toNetease(): NeteaseTrack {
    require(track.identity.sourceId == NETEASE_SOURCE_ID) { "曲目不属于网易云" }
    val value = if (payload.format == "netease.track") JSONObject(payload.encoded) else JSONObject()
    return NeteaseTrack(stableId, NeteaseOrigin.NETEASE, track.title, artistsText, album, durationMs,
        artwork.startArgb, artwork.endArgb, artwork.mark, previewUrl, track.artworkUrl,
        accessBadge = when (access.badge) { TrackAccessBadge.MEMBERSHIP -> NeteaseAccessBadge.VIP; TrackAccessBadge.PURCHASE -> NeteaseAccessBadge.PAID; null -> null },
        trialAvailable = access.trialAvailable, playable = access.playable, unavailableReason = access.reason,
        remoteKey = track.identity.remoteId, sourceAccountId = track.identity.accountId,
        providerData = if (value.length() > 0) mapOf("netease.track" to value.toString()) else emptyMap())
}
internal fun NeteasePlaylist.toCollection(account: String?) = SourceCollection(
    TrackIdentity(NETEASE_SOURCE_ID, account, remoteId()), id, title, subtitle, description, count,
    SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark), isOwned, isPlatformFavorites,
    SourcePayload("netease.playlist", 1, JSONObject().put("id", id).put("title", title).put("subtitle", subtitle)
        .put("description", description).put("count", count).put("artworkUrl", artworkUrl).put("owned", isOwned).toString()))
internal fun SourceCollection.toNetease() = NeteasePlaylist(stableId, NeteaseOrigin.NETEASE, title, subtitle, description,
    count ?: 0, artwork.startArgb, artwork.endArgb, artwork.mark, emptyList(), artwork.url, owned, accountFavorites,
    sourceAccountId = identity.accountId, remoteKey = identity.remoteId)
internal fun List<NeteaseLyricLine>.toSourceLyrics() = SourceLyrics(map { line ->
    SourceLyricLine(line.timeMs, line.text, line.words.map { SourceLyricWord(it.startMs, (it.endMs - it.startMs).coerceAtLeast(0), it.text) },
        line.translation, parserVersion = line.parserVersion)
})
