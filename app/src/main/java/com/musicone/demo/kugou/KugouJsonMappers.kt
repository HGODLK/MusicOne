package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.parseKugouSearchTracks(hasVipAccess: Boolean): List<MusicTrack> =
    optJSONObject("data")?.optJSONArray("lists")?.kugouObjects { it.toKugouTrack(hasVipAccess) }.orEmpty()

internal fun JSONObject.parseKugouRecommendedPlaylists(hasVipAccess: Boolean): List<MusicPlaylist> =
    optJSONObject("plist")?.optJSONObject("list")?.optJSONArray("info")?.kugouObjects { item ->
        item.toKugouPlaylist(hasVipAccess)
    }.orEmpty()

internal fun JSONObject.parseKugouPlaylistTracks(hasVipAccess: Boolean): List<MusicTrack> =
    optJSONObject("data")?.optJSONArray("info")?.kugouObjects { it.toKugouTrack(hasVipAccess) }.orEmpty()

internal fun JSONObject.parseKugouSearchPlaylists(): List<MusicPlaylist> =
    optJSONObject("data")?.optJSONArray("info")?.kugouObjects { it.toKugouPlaylist(false) }.orEmpty()

internal fun JSONObject.parseKugouSearchAlbums(): List<MusicPlaylist> =
    optJSONObject("data")?.optJSONArray("info")?.kugouObjects { item ->
        val id = item.firstKugouString("albumid", "album_id")
        if (id.isBlank()) throw PlatformApiException("酷狗音乐返回了无效专辑")
        val title = cleanKugouText(item.firstKugouString("albumname", "album_name")).ifBlank { "酷狗音乐专辑" }
        val colors = kugouArtworkColors(id)
        MusicPlaylist(
            id = "kugou-album:$id", source = MusicSource.KUGOU, title = title,
            subtitle = "专辑 · ${cleanKugouText(item.firstKugouString("singername", "author_name")).ifBlank { "未知歌手" }}",
            description = cleanKugouText(item.firstKugouString("intro")),
            count = item.firstKugouInt("songcount", "count"),
            artworkStart = colors.first, artworkEnd = colors.second, artworkMark = title.take(1),
            tracks = emptyList(), artworkUrl = item.firstKugouString("imgurl", "cover").kugouArtworkUrl(),
        )
    }.orEmpty()

internal fun JSONObject.toKugouTrack(hasVipAccess: Boolean): MusicTrack {
    val standardHash = firstKugouString("FileHash", "filehash", "hash", "Hash")
    val hqHash = firstKugouString("HQFileHash", "hqfilehash", "320hash")
    val sqHash = firstKugouString("SQFileHash", "sqfilehash", "sqhash")
    val resHash = firstKugouString("ResFileHash", "resfilehash", "res_hash")
    val fallbackHash = listOf(standardHash, hqHash, sqHash, resHash).firstOrNull(String::isNotBlank).orEmpty()
    if (fallbackHash.isBlank()) throw PlatformApiException("酷狗音乐返回了无效歌曲")
    val fileName = firstKugouString("FileName", "filename", "name")
    val explicitTitle = firstKugouString("SongName", "songname", "song_name")
    val explicitArtist = firstKugouString("SingerName", "singername", "author_name")
    val split = fileName.split(" - ", limit = 2)
    val title = cleanKugouText(explicitTitle.ifBlank { split.getOrNull(1).orEmpty().ifBlank { fileName } })
        .replace(Regex("\\.(mp3|flac|ogg|wav)$", RegexOption.IGNORE_CASE), "")
    val artist = cleanKugouText(explicitArtist.ifBlank { split.firstOrNull().orEmpty() }).ifBlank { "未知歌手" }
    val trans = optJSONObject("trans_param") ?: optJSONObject("TransParam")
    val payType = firstKugouInt("PayType", "paytype", "pay_type")
    val privilege = firstKugouInt("Privilege", "privilege")
    val purchased = firstKugouInt("is_buy", "is_bought", "purchased", "has_buy") == 1
    val paidOnly = payType in setOf(1, 2, 4) || firstKugouInt("feetype", "fee_type") > 0
    val needsVip = payType == 3 || privilege == 8 || privilege == 10
    val trialAvailable = trans?.optJSONObject("hash_offset") != null ||
        trans?.optString("clip_hash").orEmpty().isNotBlank() ||
        firstKugouInt("FailProcess", "failprocess", "fail_process") == 4 || privilege == 8
    val access = kugouTrackAccess(purchased, paidOnly, needsVip, hasVipAccess, trialAvailable)
    val albumAudioId = firstKugouString("MixSongID", "mixsongid", "album_audio_id", "ID", "id")
    val albumId = firstKugouString("AlbumID", "album_id", "albumid")
    val artwork = firstKugouString("Image", "image", "cover", "imgurl")
        .ifBlank { trans?.firstKugouString("union_cover").orEmpty() }
        .kugouArtworkUrl()
    val colors = kugouArtworkColors(fallbackHash)
    val qualities = buildMap {
        if (standardHash.isNotBlank()) put(AudioQuality.STANDARD, standardHash)
        if (hqHash.isNotBlank()) put(AudioQuality.EXHIGH, hqHash)
        if (sqHash.isNotBlank()) put(AudioQuality.LOSSLESS, sqHash)
        if (resHash.isNotBlank()) put(AudioQuality.HI_RES, resHash)
    }
    return MusicTrack(
        id = "kugou-${standardHash.ifBlank { fallbackHash }}",
        source = MusicSource.KUGOU,
        title = title.ifBlank { "未知歌曲" },
        artists = artist,
        album = cleanKugouText(firstKugouString("AlbumName", "albumname", "album_name", "remark")),
        durationMs = firstKugouLong("Duration", "duration", "timelen").let { if (it in 1..99_999) it * 1_000 else it },
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        previewUrl = "",
        artworkUrl = artwork,
        catalogId = albumAudioId,
        mediaId = albumId,
        qualityIds = qualities,
        accessBadge = access.badge,
        trialAvailable = access.trialAvailable,
        playable = access.playable,
        unavailableReason = if (!access.playable) "当前账号无权播放这首歌曲" else null,
    )
}

internal fun kugouTrackAccess(
    purchased: Boolean,
    paidOnly: Boolean,
    needsVip: Boolean,
    hasVipAccess: Boolean,
    trialAvailable: Boolean,
): MusicTrackAccess {
    val badge = when {
        purchased -> null
        paidOnly -> MusicAccessBadge.PAID
        needsVip && !hasVipAccess -> MusicAccessBadge.VIP
        else -> null
    }
    return MusicTrackAccess(badge, badge != null && trialAvailable, badge == null || trialAvailable)
}

private fun JSONObject.toKugouPlaylist(hasVipAccess: Boolean): MusicPlaylist {
    val id = firstKugouString("specialid", "special_id", "global_collection_id")
    if (id.isBlank()) throw PlatformApiException("酷狗音乐返回了无效歌单")
    val title = firstKugouString("specialname", "name").ifBlank { "酷狗音乐歌单" }
    val colors = kugouArtworkColors(id)
    return MusicPlaylist(
        id = "kugou-$id",
        source = MusicSource.KUGOU,
        title = title,
        subtitle = firstKugouString("username", "nickname").ifBlank { "酷狗音乐推荐" },
        description = firstKugouString("intro").ifBlank { "来自酷狗音乐的推荐" },
        count = firstKugouInt("songcount", "count"),
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        tracks = optJSONArray("songs")?.kugouObjects { it.toKugouTrack(hasVipAccess) }.orEmpty(),
        artworkUrl = firstKugouString("imgurl", "pic").kugouArtworkUrl(),
    )
}

private fun JSONObject.firstKugouString(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else opt(name)?.toString()?.trim()?.takeIf(String::isNotBlank)
}.orEmpty()

private fun JSONObject.firstKugouInt(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else opt(name)?.toString()?.toDoubleOrNull()?.toInt()
} ?: 0

private fun JSONObject.firstKugouLong(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else opt(name)?.toString()?.toDoubleOrNull()?.toLong()
} ?: 0L

private fun <T> JSONArray.kugouObjects(transform: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let { item ->
        runCatching { transform(item) }.getOrNull()?.let(::add)
    }
}

private fun cleanKugouText(value: String): String = value
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .trim()

private fun String.kugouArtworkUrl(): String? = trim().takeIf(String::isNotBlank)?.let {
    it.replace("{size}", "500").replaceFirst("http://", "https://")
}

private fun kugouArtworkColors(key: String): Pair<Long, Long> {
    val palettes = listOf(
        0xFFF1A14BL to 0xFF9A5138L,
        0xFFF4BA67L to 0xFF7A6541L,
        0xFFE59065L to 0xFF70475EL,
        0xFFF2C57CL to 0xFF5A746EL,
    )
    return palettes[(key.hashCode() and Int.MAX_VALUE) % palettes.size]
}
