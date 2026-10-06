package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun JSONObject.parseQqSearchTracks(hasVipAccess: Boolean = false): List<MusicTrack> =
    optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
        ?.qqObjects { it.toQqTrack(hasVipAccess) }
        ?.distinctBy(MusicTrack::id)
        .orEmpty()

internal fun JSONObject.parseQqRadioTracks(hasVipAccess: Boolean = false): List<MusicTrack> {
    val data = optJSONObject("songlist")?.optJSONObject("data")
        ?: optJSONObject("request")?.optJSONObject("data")
        ?: return emptyList()
    return (data.optJSONArray("tracks") ?: data.optJSONArray("track_list"))
        ?.qqObjects { item ->
            (item.optJSONObject("track") ?: item.optJSONObject("Track") ?: item).toQqTrack(hasVipAccess)
        }
        .orEmpty()
}

internal fun JSONObject.parseQqPlaylist(fallback: MusicPlaylist, hasVipAccess: Boolean = false): MusicPlaylist {
    val value = optJSONArray("cdlist")?.optJSONObject(0)
        ?: throw PlatformApiException("QQ 音乐没有返回歌单内容")
    val title = decodeQqPlaylistDescription(value.optString("dissname")).ifBlank { fallback.title }
    val tracks = value.optJSONArray("songlist")?.qqObjects { it.toQqTrack(hasVipAccess) }.orEmpty()
    return fallback.copy(
        title = title,
        subtitle = decodeQqPlaylistDescription(value.optString("nickname")).ifBlank { "QQ 音乐" },
        description = decodeQqPlaylistDescription(
            value.optString("desc").ifBlank { "来自 QQ 音乐的歌单" },
        ),
        count = value.optInt("songnum"),
        tracks = tracks,
        artworkMark = title.take(1),
        artworkUrl = qqPlaylistDetailArtwork(fallback.artworkUrl, value.qqCoverUrl(), tracks.isNotEmpty()),
    )
}

/** 空歌单详情常返回 QQ 通用空封面，此时保留入口歌单原有封面或本地兜底。 */
internal fun qqPlaylistDetailArtwork(
    currentArtwork: String?,
    detailArtwork: String?,
    hasTracks: Boolean,
): String? = if (hasTracks) {
    detailArtwork?.takeIf(String::isNotBlank) ?: currentArtwork?.takeIf(String::isNotBlank)
} else {
    currentArtwork?.takeIf(String::isNotBlank)
}

internal fun JSONObject.toQqTrack(hasVipAccess: Boolean = false): MusicTrack {
    val mid = firstQqString("songmid", "mid")
    val songId = firstQqLong("songid", "id").takeIf { it > 0 }?.toString().orEmpty()
    if (!isValidQqTrackMid(mid)) throw PlatformApiException("QQ 音乐返回了无效歌曲")
    val title = decodeQqPlaylistDescription(firstQqString("songname", "name")).ifBlank { "未知歌曲" }
    val albumObject = optJSONObject("album")
    val album = firstQqString("albumname").ifBlank { albumObject?.optString("name").orEmpty() }
    val albumMid = firstQqString("albummid").ifBlank {
        albumObject?.optString("mid").orEmpty().ifBlank { albumObject?.optString("pmid").orEmpty() }
    }
    val singers = optJSONArray("singer")?.qqObjects { it.optString("name") }.orEmpty().filter(String::isNotBlank)
    val file = optJSONObject("file")
    val mediaMid = file?.firstQqString("media_mid", "mediaMid").orEmpty()
        .ifBlank { firstQqString("strMediaMid", "media_mid", "mediaMid") }
        .ifBlank { mid }
    val preview = optJSONObject("preview")
    val trialAvailable = (file?.firstQqLong("size_try", "sizetry") ?: 0L) > 0L ||
        (preview?.firstQqLong("try_size", "trysize") ?: 0L) > 0L ||
        (file?.firstQqLong("try_begin", "trybegin") ?: 0L) > 0L ||
        (file?.firstQqLong("try_end", "tryend") ?: 0L) > 0L ||
        optInt("try_begin", 0) > 0 || optInt("try_end", 0) > 0
    val rawAccess = qqTrackAccessInfo(trialAvailable)
    val access = rawAccess.access("", hasVipAccess)
    val colors = qqArtworkColors(title)
    return MusicTrack(
        id = "qq-$mid",
        songMid = mid,
        artistRefs = optJSONArray("singer")?.qqObjects {
            MusicArtist(it.searchText("mid", "singerMid"), it.searchText("name", "singerName"))
        }.orEmpty().filter { it.mid.isNotBlank() && it.name.isNotBlank() },
        albumMid = albumMid,
        source = MusicSource.QQ,
        title = title,
        artists = singers.joinToString("、").ifBlank { "未知歌手" },
        album = album,
        durationMs = firstQqLong("interval").coerceAtLeast(0L) * 1_000L,
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        previewUrl = "",
        artworkUrl = albumMid.takeIf(String::isNotBlank)?.let {
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg"
        },
        catalogId = songId,
        mediaId = mediaMid,
        providerType = firstQqInt("type") ?: 0,
        qualityIds = qqTrackQualityIds(this, file, mediaMid),
        qqAccess = rawAccess,
        accessBadge = access.badge,
        trialAvailable = access.trialAvailable,
        playable = access.playable,
        unavailableReason = if (!access.playable) "当前账号无权播放这首歌曲" else null,
    )
}

private fun qqTrackQualityIds(track: JSONObject, file: JSONObject?, mediaMid: String): Map<AudioQuality, String> {
    if (mediaMid.isBlank()) return emptyMap()
    fun size(vararg names: String): Long = maxOf(
        file?.firstQqLong(*names) ?: 0L,
        track.firstQqLong(*names),
    )
    return qqQualityIdsFromFileSizes(
        mediaMid,
        mapOf(
            AudioQuality.STANDARD to maxOf(
                size("size_128mp3", "size128mp3", "size128"),
                size("size_192aac", "size_96aac", "size_48aac", "size192aac", "size96aac", "size48aac"),
            ),
            AudioQuality.EXHIGH to size("size_320mp3", "size320mp3", "size320"),
            AudioQuality.LOSSLESS to size("size_flac", "sizeflac"),
            AudioQuality.DOLBY to size("size_dolby", "sizeDolby", "sizedolby"),
        ),
    )
}

internal fun qqQualityIdsFromFileSizes(
    mediaMid: String,
    sizes: Map<AudioQuality, Long>,
): Map<AudioQuality, String> = if (mediaMid.isBlank()) emptyMap() else buildMap {
    sizes.forEach { (quality, size) -> if (size > 0L) put(quality, mediaMid) }
}

internal fun decodeQqPlaylistDescription(raw: String): String {
    var text = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
    repeat(2) {
        val decoded = QQ_HTML_ENTITY.replace(text) { match ->
            val entity = match.groupValues[1]
            val codePoint = when {
                entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                else -> null
            }
            when {
                codePoint != null && Character.isValidCodePoint(codePoint) ->
                    String(Character.toChars(codePoint))
                entity.equals("amp", true) -> "&"
                entity.equals("lt", true) -> "<"
                entity.equals("gt", true) -> ">"
                entity.equals("quot", true) -> "\""
                entity.equals("apos", true) || entity == "#39" -> "'"
                entity.equals("nbsp", true) -> " "
                else -> match.value
            }
        }
        if (decoded == text) return@repeat
        text = decoded
    }
    return text
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private val QQ_HTML_ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);", RegexOption.IGNORE_CASE)

private fun JSONObject.firstQqString(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    optString(name).takeIf(String::isNotBlank)
}.orEmpty()

private fun JSONObject.firstQqInt(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
    takeIf { has(name) && !isNull(name) }?.optInt(name)
}

private fun JSONObject.firstQqLong(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    takeIf { has(name) && !isNull(name) }?.optLong(name)
} ?: 0L

private fun <T> JSONArray.qqObjects(transform: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let { value ->
        runCatching { transform(value) }.getOrNull()?.let(::add)
    }
}

internal fun MusicTrack.needsQqMetadataEnrichment(): Boolean =
    // 播放前始终以详情接口重新确认，不能信任旧快照或推荐接口中的不完整文件尺寸。
    source == MusicSource.QQ

internal fun MusicTrack.mergeQqTrackMetadata(detail: MusicTrack): MusicTrack = copy(
    title = detail.title.ifBlank { title },
    artists = detail.artists.ifBlank { artists },
    album = detail.album.ifBlank { album },
    durationMs = detail.durationMs.takeIf { it > 0L } ?: durationMs,
    artworkUrl = detail.artworkUrl ?: artworkUrl,
    catalogId = detail.catalogId.ifBlank { catalogId },
    mediaId = detail.mediaId.ifBlank { mediaId },
    albumMid = detail.albumMid.ifBlank { albumMid },
    artistRefs = detail.artistRefs.ifEmpty { artistRefs },
    songMid = detail.songMid.ifBlank { detail.remoteId().takeUnless { it.all(Char::isDigit) }.orEmpty() }.ifBlank { songMid },
    providerType = detail.providerType,
    qqAccess = detail.qqAccess?.takeIf { it.complete } ?: qqAccess,
    accessBadge = if (detail.qqAccess?.complete == true) detail.accessBadge else accessBadge,
    trialAvailable = if (detail.qqAccess?.complete == true) detail.trialAvailable else trialAvailable,
    playable = if (detail.qqAccess?.complete == true) detail.playable else playable,
    unavailableReason = if (detail.qqAccess?.complete == true) detail.unavailableReason else unavailableReason,
    // 详情中的文件尺寸是权威值；空集合也必须覆盖旧快照，避免错误保留 FLAC。
    qualityIds = detail.qualityIds,
)

/** 歌曲 MID 用于换票及歌词查询，文件 MID 只用于拼接媒体文件名，两者不能混用。 */
internal fun MusicTrack.qqPlaybackMid(): String = songMid.ifBlank {
    remoteId().takeUnless { it.all(Char::isDigit) }.orEmpty()
}

/** 信息流短卡补全后改用服务器 MID 作为统一身份，供播放队列与“我喜欢”排重。 */
internal fun MusicTrack.withCanonicalQqIdentity(): MusicTrack {
    if (source != MusicSource.QQ) return this
    val mid = qqPlaybackMid().takeIf(::isValidQqTrackMid) ?: return this
    return copy(id = "qq-$mid", songMid = mid)
}

/** 元数据验证失败时仅保留保守的标准档，绝不沿用旧快照中的高级音质。 */
internal fun MusicTrack.withConservativeQqQuality(): MusicTrack {
    if (source != MusicSource.QQ) return this
    val id = mediaId.ifBlank { remoteId() }
    return copy(qualityIds = mapOf(AudioQuality.STANDARD to id))
}

internal fun MusicTrack.withQqArtworkFallback(candidates: List<MusicTrack>): MusicTrack {
    if (!artworkUrl.isNullOrBlank()) return this
    val normalizedArtists = artists.qqArtworkArtistTokens()
    val candidate = candidates.asSequence()
        .filter { it.artworkUrl?.isNotBlank() == true }
        .mapNotNull { value -> qqArtworkTitleMatchScore(title, value.title)?.let { Triple(value, it, value.artists.qqArtworkArtistTokens()) } }
        .filter { value ->
            normalizedArtists.isEmpty() || value.third.isEmpty() ||
                normalizedArtists.any { artist -> value.third.any { other -> artist in other || other in artist } }
        }
        .minWithOrNull(compareBy<Triple<MusicTrack, Int, List<String>>> { it.second }
            .thenBy { kotlin.math.abs(it.first.durationMs - durationMs) })
        ?.first
        ?: return this
    return copy(
        artworkUrl = candidate.artworkUrl,
        album = album.ifBlank { candidate.album },
    )
}

internal fun isValidQqTrackMid(mid: String): Boolean = mid.isNotBlank() && mid != "0"

internal fun String.qqHttpsUrl(): String? = trim().takeIf(String::isNotBlank)
    ?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }

private fun String.qqArtworkComparableTitle(dropVersionSuffix: Boolean): String {
    val compact = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase()
        .replace('（', '(')
        .replace('）', ')')
        .replace(Regex("\\s+"), "")
    val selected = if (dropVersionSuffix) compact.substringBefore('(') else compact
    return selected.replace(Regex("[^\\p{L}\\p{N}()]"), "")
}

internal fun qqArtworkTitleMatchScore(left: String, right: String): Int? {
    val leftExact = left.qqArtworkComparableTitle(dropVersionSuffix = false)
    val rightExact = right.qqArtworkComparableTitle(dropVersionSuffix = false)
    if (leftExact.isNotBlank() && leftExact == rightExact) return 0
    val leftBase = left.qqArtworkComparableTitle(dropVersionSuffix = true)
    val rightBase = right.qqArtworkComparableTitle(dropVersionSuffix = true)
    if (leftBase.isNotBlank() && leftBase == rightBase) return 1
    val shorter = minOf(leftBase.length, rightBase.length)
    return if (shorter >= 3 && (leftBase in rightBase || rightBase in leftBase)) 2 else null
}

private fun String.qqArtworkArtistTokens(): List<String> =
    Normalizer.normalize(this, Normalizer.Form.NFKC)
        .split(Regex("[、,，;/；&＆·]+"))
        .map { it.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") }
        .filter { it.isNotBlank() && it != "未知歌手" }

/** 同一个歌曲或歌单名称始终生成相同的双色占位封面。 */
internal fun qqArtworkColors(name: String): Pair<Long, Long> {
    val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase().ifBlank { "music" }
    val hash = normalized.fold(0x811C9DC5u) { value, char ->
        (value xor char.code.toUInt()) * 0x01000193u
    }
    val hue = (hash % 360u).toFloat()
    val hueDistance = 34f + ((hash shr 9) % 58u).toFloat()
    val startSaturation = .54f + ((hash shr 17) % 13u).toFloat() / 100f
    val startLightness = .50f + ((hash shr 22) % 8u).toFloat() / 100f
    val endSaturation = .48f + ((hash shr 5) % 13u).toFloat() / 100f
    val endLightness = .29f + ((hash shr 13) % 8u).toFloat() / 100f
    return hslToQqArtworkArgb(hue, startSaturation, startLightness) to
        hslToQqArtworkArgb((hue + hueDistance) % 360f, endSaturation, endLightness)
}

private fun hslToQqArtworkArgb(hue: Float, saturation: Float, lightness: Float): Long {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = hue / 60f
    val second = chroma * (1f - abs(sector % 2f - 1f))
    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val match = lightness - chroma / 2f
    fun channel(value: Float) = ((value + match) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
