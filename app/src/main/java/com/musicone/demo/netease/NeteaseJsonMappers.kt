package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.toMusicAccount(): MusicAccount {
    val profile = optJSONObject("profile") ?: throw NeteaseApiException("网易云登录状态已失效", 301)
    val userId = profile.optLong("userId").takeIf { it > 0 }
        ?: optJSONObject("account")?.optLong("id")?.takeIf { it > 0 }
        ?: throw NeteaseApiException("网易云没有返回账户信息")
    return MusicAccount(
        source = MusicSource.NETEASE,
        userId = userId.toString(),
        nickname = profile.optString("nickname"),
        avatarUrl = profile.optString("avatarUrl").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
        hasVipAccess = profile.optInt("vipType", profile.optInt("viptype", 0)) > 0 ||
            optJSONObject("account")?.optInt("vipType", 0)?.let { it > 0 } == true,
        backgroundUrl = profile.optString("backgroundUrl").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
        signature = profile.optString("signature"),
        follows = profile.optInt("follows").coerceAtLeast(0),
        followers = profile.optInt("followeds").coerceAtLeast(0),
    )
}

internal fun JSONObject.parseUserPlaylists(ownerUserId: String = ""): List<MusicPlaylist> =
    optJSONArray("playlist")?.mapObjects { value ->
        val id = value.optLong("id").toString()
        val title = value.optString("name").ifBlank { "网易云歌单" }
        val colors = artworkColors(id)
        MusicPlaylist(
            id = "netease-$id",
            source = MusicSource.NETEASE,
            title = title,
            subtitle = value.optJSONObject("creator")?.optString("nickname").orEmpty()
                .ifBlank { "网易云音乐" },
            description = value.optString("description").takeUnless { it.equals("null", ignoreCase = true) }.orEmpty(),
            count = value.optInt("trackCount").coerceAtLeast(0),
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = title.take(1),
            tracks = emptyList(),
            artworkUrl = value.optString("coverImgUrl").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
            isOwned = ownerUserId.isNotBlank() &&
                value.optJSONObject("creator")?.optLong("userId")?.toString() == ownerUserId,
        )
    }.orEmpty()

internal fun JSONObject.parseSearchTracks(hasVipAccess: Boolean = false): List<MusicTrack> {
    val songs = optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
    return songs.mapObjects { song -> song.toMusicTrack(song.optJSONObject("privilege"), hasVipAccess) }
}

internal fun JSONObject.parseSongDetails(hasVipAccess: Boolean = false): List<MusicTrack> {
    val privilegeById = optJSONArray("privileges")?.mapObjects { privilege ->
        privilege.optLong("id") to privilege
    }?.toMap().orEmpty()
    return optJSONArray("songs")?.mapObjects { song ->
        song.toMusicTrack(privilegeById[song.optLong("id")] ?: song.optJSONObject("privilege"), hasVipAccess)
    }.orEmpty()
}

internal fun JSONObject.parseRecommendedPlaylists(): List<MusicPlaylist> =
    optJSONArray("result")?.mapObjects { item ->
        val id = item.optLong("id").toString()
        val name = item.optString("name").ifBlank { "网易云歌单" }
        val colors = artworkColors(id)
        MusicPlaylist(
            id = "netease-$id",
            source = MusicSource.NETEASE,
            title = name,
            subtitle = item.optString("copywriter").ifBlank { "网易云推荐" },
            description = item.optString("copywriter").ifBlank { "来自网易云音乐的推荐" },
            count = item.optInt("trackCount"),
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = name.take(1),
            tracks = emptyList(),
            artworkUrl = item.optString("picUrl").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
        )
    }.orEmpty()

internal fun JSONObject.parseDailyRecommendedTracks(hasVipAccess: Boolean = false): List<MusicTrack> {
    val data = optJSONObject("data") ?: return emptyList()
    val privilegeById = data.optJSONArray("privileges")?.mapObjects { privilege ->
        privilege.optLong("id") to privilege
    }?.toMap().orEmpty()
    return data.optJSONArray("dailySongs")?.mapObjects { song ->
        song.toMusicTrack(privilegeById[song.optLong("id")] ?: song.optJSONObject("privilege"), hasVipAccess)
    }.orEmpty()
}

internal fun JSONObject.parsePublicRecommendedTracks(hasVipAccess: Boolean = false): List<MusicTrack> =
    optJSONArray("result")?.mapObjects { item ->
        val song = item.optJSONObject("song") ?: item
        song.toMusicTrack(song.optJSONObject("privilege"), hasVipAccess)
    }.orEmpty()

internal fun JSONObject.parsePlaylistShell(): Pair<MusicPlaylist, List<String>> {
    val value = optJSONObject("playlist") ?: throw NeteaseApiException("网易云没有返回歌单内容")
    val id = value.optLong("id").toString()
    val name = value.optString("name").ifBlank { "网易云歌单" }
    val colors = artworkColors(id)
    val playlist = MusicPlaylist(
        id = "netease-$id",
        source = MusicSource.NETEASE,
        title = name,
        subtitle = value.optJSONObject("creator")?.optString("nickname").orEmpty().ifBlank { "网易云音乐" },
        description = value.optString("description")
            .takeUnless { it.equals("null", ignoreCase = true) }
            .orEmpty()
            .ifBlank { "来自网易云音乐的歌单" },
        count = value.optInt("trackCount"),
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = name.take(1),
        tracks = emptyList(),
        artworkUrl = value.optString("coverImgUrl").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
    )
    val ids = value.optJSONArray("trackIds")?.mapObjects { it.optLong("id").toString() }.orEmpty()
    return playlist to ids
}

internal fun JSONObject.parsePlaylistEmbeddedTracks(hasVipAccess: Boolean = false): List<MusicTrack> {
    val privilegeById = optJSONArray("privileges")?.mapObjects { privilege ->
        privilege.optLong("id").toString() to privilege
    }?.toMap().orEmpty()
    return optJSONObject("playlist")?.optJSONArray("tracks")?.mapObjects { song ->
        song.toMusicTrack(privilegeById[song.optLong("id").toString()] ?: song.optJSONObject("privilege"), hasVipAccess)
    }.orEmpty()
}

internal fun missingPlaylistTrackIds(trackIds: List<String>, tracks: List<MusicTrack>): List<String> {
    val loadedIds = tracks.mapTo(hashSetOf(), MusicTrack::remoteId)
    return trackIds.filterNot(loadedIds::contains)
}

internal fun orderedPlaylistTracks(trackIds: List<String>, tracks: List<MusicTrack>): List<MusicTrack> {
    val tracksById = tracks.associateBy(MusicTrack::remoteId)
    return trackIds.mapNotNull(tracksById::get)
}

private fun JSONObject.toMusicTrack(
    privilege: JSONObject? = optJSONObject("privilege"),
    hasVipAccess: Boolean = false,
): MusicTrack {
    val remoteId = optLong("id").toString()
    val title = optString("name").ifBlank { "未知歌曲" }
    val album = optJSONObject("al") ?: optJSONObject("album")
    val artistValues = optJSONArray("ar") ?: optJSONArray("artists")
    val artists = artistValues?.mapObjects { it.optString("name") }.orEmpty().filter(String::isNotBlank)
    val duration = optLong("dt").takeIf { it > 0L } ?: optLong("duration").coerceAtLeast(0L)
    val access = neteaseTrackAccess(
        fee = privilege?.optInt("fee", -1)?.takeIf { it >= 0 } ?: optInt("fee", 0),
        playBitRate = privilege?.optInt("pl"),
        trialBitRate = privilege?.optInt("fl") ?: 0,
        trialConsumable = privilege?.optJSONObject("freeTrialPrivilege")?.optBoolean("resConsumable") == true,
        status = privilege?.optInt("st") ?: 0,
        hasVipAccess = hasVipAccess,
    )
    val colors = artworkColors(remoteId)
    return MusicTrack(
        id = "netease-$remoteId",
        source = MusicSource.NETEASE,
        title = title,
        artists = artists.joinToString("、").ifBlank { "未知歌手" },
        album = album?.optString("name").orEmpty(),
        durationMs = duration,
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        previewUrl = "",
        artworkUrl = album?.optString("picUrl")?.takeIf(String::isNotBlank)?.asNeteaseHttpsUrl(),
        accessBadge = access.badge,
        trialAvailable = access.trialAvailable,
        playable = access.playable,
        unavailableReason = if (access.playable) null else "当前歌曲暂不可播放",
    )
}

internal data class MusicTrackAccess(
    val badge: MusicAccessBadge?,
    val trialAvailable: Boolean,
    val playable: Boolean,
)

internal fun neteaseTrackAccess(
    fee: Int,
    playBitRate: Int?,
    trialBitRate: Int,
    trialConsumable: Boolean,
    status: Int,
    hasVipAccess: Boolean = false,
): MusicTrackAccess {
    val hasPrivilege = playBitRate != null
    val hasFullAccess = (playBitRate ?: 0) > 0
    val accountEntitled = fee == 1 && hasVipAccess
    val trialAvailable = !hasFullAccess && !accountEntitled && (trialBitRate > 0 || trialConsumable)
    return MusicTrackAccess(
        badge = when {
            hasFullAccess -> null
            accountEntitled -> null
            fee == 4 -> MusicAccessBadge.PAID
            fee == 1 -> MusicAccessBadge.VIP
            else -> null
        },
        trialAvailable = trialAvailable,
        playable = status >= 0 && (!hasPrivilege || hasFullAccess || accountEntitled || trialAvailable),
    )
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }

private fun artworkColors(key: String): Pair<Long, Long> {
    val palettes = listOf(
        0xFFE8706DL to 0xFF8B4258L,
        0xFF79B6C9L to 0xFF4A5C8DL,
        0xFFF0B56CL to 0xFFB85C70L,
        0xFF98B99AL to 0xFF4D7180L,
    )
    return palettes[(key.hashCode() and Int.MAX_VALUE) % palettes.size]
}

internal fun String.asNeteaseHttpsUrl(): String = trim().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

internal fun parseLrc(value: String): List<TimedLyric> {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return value.lineSequence().flatMap { line ->
        val text = line.replace(timestamp, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        timestamp.findAll(line).map { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val milliseconds = when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
            }
            TimedLyric((minutes * 60L + seconds) * 1_000L + milliseconds, text)
        }
    }.sortedBy(TimedLyric::timeMs).toList()
}
