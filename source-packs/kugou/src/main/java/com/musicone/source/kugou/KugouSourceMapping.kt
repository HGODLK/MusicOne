package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONObject

internal const val KUGOU_SOURCE_ID = "com.musicone.source.kugou"
internal const val KUGOU_PAGE_COLOR = 0xFF009AF3L
internal object KugouSourceMapping {
    fun track(value: MusicTrack, account: String?): SourceTrackDetails {
        val data = buildMap {
            put("catalogId", value.catalogId); put("mediaId", value.mediaId)
            put("qualityIds", JSONObject(value.qualityIds.mapKeys { it.key.name }).toString())
            value.kugouAccess?.bindPurchase(account)?.let { put("kugouAccess", it.toJson().toString()) }
        }
        return SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, account, value.remoteId()), value.title,
            listOf(value.artists), value.artworkUrl, data), value.id, value.album, value.durationMs,
            SourceArtwork(value.artworkUrl, value.artworkStart, value.artworkEnd, value.artworkMark),
            artists = value.artistReferences, albumRef = value.albumReference,
            access = TrackAccessInfo(when (value.accessBadge) { MusicAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP; MusicAccessBadge.PAID -> TrackAccessBadge.PURCHASE; null -> null },
                value.trialAvailable, value.playable, value.unavailableReason),
            qualities = value.qualityIds.map { (quality, hash) -> SourceAudioQuality(quality.name, quality.label,
                payload = SourcePayload("kugou.quality", 1, hash)) },
            lyrics = value.lyrics.takeIf { it.isNotEmpty() }?.let(::lyrics),
            payload = SourcePayload("kugou.track", 1, JSONObject(data).toString()), artistsText = value.artists, previewUrl = value.previewUrl)
    }
    fun track(value: SourceTrackDetails): MusicTrack {
        require(value.track.identity.sourceId == KUGOU_SOURCE_ID)
        val data = if (value.payload.encoded.startsWith("{")) JSONObject(value.payload.encoded) else JSONObject()
        fun field(key: String) = value.track.opaqueData[key] ?: data.optString(key)
        val qualities = runCatching { JSONObject(field("qualityIds")) }.getOrDefault(JSONObject())
        val hashes = buildMap<AudioQuality, String> {
            qualities.keys().forEach { key -> runCatching { AudioQuality.valueOf(key) }.getOrNull()?.let { put(it, qualities.getString(key)) } }
            value.qualities.forEach { item -> runCatching { AudioQuality.valueOf(item.id) }.getOrNull()?.let {
                if (item.payload.format == "kugou.quality" && item.payload.encoded.isNotBlank()) put(it, item.payload.encoded)
            } }
        }
        return MusicTrack(value.stableId, title = value.track.title, artists = value.artistsText,
            album = value.album, durationMs = value.durationMs, artworkStart = value.artwork.startArgb,
            artworkEnd = value.artwork.endArgb, artworkMark = value.artwork.mark, artworkUrl = value.artwork.url,
            catalogId = field("catalogId"), mediaId = field("mediaId"), qualityIds = hashes,
            accessBadge = when (value.access.badge) { TrackAccessBadge.MEMBERSHIP -> MusicAccessBadge.VIP; TrackAccessBadge.PURCHASE -> MusicAccessBadge.PAID; null -> null },
            trialAvailable = value.access.trialAvailable, playable = value.access.playable,
            unavailableReason = value.access.reason, previewUrl = value.previewUrl,
            artistReferences = value.artists, albumReference = value.albumRef, kugouAccess = value.kugouRawAccess())
    }
    fun collection(value: MusicPlaylist, account: String?): SourceCollection = SourceCollection(
        TrackIdentity(KUGOU_SOURCE_ID, account, value.remoteId()), value.id, value.title, value.subtitle, value.description,
        value.count, SourceArtwork(value.artworkUrl, value.artworkStart, value.artworkEnd, value.artworkMark),
        value.isOwned, value.isPlatformFavorites,
        SourcePayload("kugou.collection", 1, JSONObject().put("id", value.id).toString()))
    fun collection(value: SourceCollection): MusicPlaylist {
        require(value.identity.sourceId == KUGOU_SOURCE_ID)
        return MusicPlaylist(value.stableId, title = value.title, subtitle = value.subtitle, description = value.description,
            count = value.count ?: 0, artworkStart = value.artwork.startArgb, artworkEnd = value.artwork.endArgb,
            artworkMark = value.artwork.mark, artworkUrl = value.artwork.url, isOwned = value.owned,
            isPlatformFavorites = value.accountFavorites)
    }
    fun account(value: MusicAccount): SourceAccount = SourceAccount(value.userId, value.nickname, value.avatarUrl,
        membership = if (value.hasVipAccess) "member" else null, backgroundUrl = value.backgroundUrl,
        signature = value.signature, follows = value.follows.toLong(), followers = value.followers.toLong())
    fun lyrics(value: List<TimedLyric>) = SourceLyrics(value.map { line -> SourceLyricLine(line.timeMs, line.text,
        line.words.map { SourceLyricWord(it.startMs, it.endMs - it.startMs, it.text) }, line.translation, line.transliteration, line.parserVersion) })
}
