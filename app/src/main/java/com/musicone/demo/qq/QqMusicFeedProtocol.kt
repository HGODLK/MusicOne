package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

internal enum class QqMusicFeedSection {
    SONG_RECOMMENDATION,
    MUSIC_FLOW,
}

internal sealed interface QqMusicFeedCard {
    val key: String
    val section: QqMusicFeedSection

    data class Playlist(
        val playlist: MusicPlaylist,
        override val section: QqMusicFeedSection = QqMusicFeedSection.MUSIC_FLOW,
    ) : QqMusicFeedCard {
        override val key = "playlist:${playlist.id}"
    }

    data class Song(
        val track: MusicTrack,
        val recommendationTitle: String,
        val lookupQuery: String? = null,
        override val section: QqMusicFeedSection = QqMusicFeedSection.MUSIC_FLOW,
    ) : QqMusicFeedCard {
        override val key = "song:${track.id}"
    }

    /** 官方三行货架：一个横向页面对应服务端返回的一组歌曲。 */
    data class SongShelf(
        val shelfId: String,
        val title: String,
        val pages: List<List<Song>>,
        override val section: QqMusicFeedSection = QqMusicFeedSection.SONG_RECOMMENDATION,
    ) : QqMusicFeedCard {
        // 同一货架换歌仍保留身份；同 ID 下的不同主题标题是独立推荐，不能合并成一栏。
        override val key = if (section == QqMusicFeedSection.SONG_RECOMMENDATION) {
            "song-shelf:$shelfId:${title.trim().replace(Regex("\\s+"), " ")}"
        } else "song-shelf:${shelfId.ifBlank { title }}:${pages.flatten().joinToString(",") { it.track.id }}"
    }

    data class SongGroup(
        val recommendationTitle: String,
        val tracks: List<MusicTrack>,
        val trackRecommendationTitles: List<String> = emptyList(),
        override val section: QqMusicFeedSection = QqMusicFeedSection.MUSIC_FLOW,
    ) : QqMusicFeedCard {
        override val key = "song-group:${tracks.joinToString(",") { it.id }}"
    }
}

internal data class QqMusicFeedPage(
    val cards: List<QqMusicFeedCard>,
    val shelfIds: List<String>,
    val shelfCount: Int,
    val uniqueKeys: List<String>,
    val loadMark: Int,
)

internal fun qqMusicFeedRequest(
    page: Int,
    shelfCount: Int,
    shelfIds: List<String>,
    uniqueKeys: List<String> = emptyList(),
): JSONObject {
    val ext = JSONObject()
        .put("bluetooth", "0")
        .put("has_login", "1")
        .put("no_need_sound_quality", "1")
        .put("ifRadarBlock", "0")
        .put("nopack_people", "0")
        .put("auto_folding_time", "0")
        // 官方首次请求/刷新使用该标记让推荐服务重新计算个性化内容。
        .put("first_req_time", if (page == 1) "1" else "0")
        .put("is_20_style", "1")
        .put("client_time_zone", TimeZone.getDefault().id)
    val param = JSONObject()
        .put("direction", if (page == 1) 0 else 1)
        .put("page", page)
        .put("s_num", shelfCount)
        .put("client_time", System.currentTimeMillis() / 1000)
        .put("v_cache", JSONArray(shelfIds))
        .put("ext", ext)
    // 官方首屏不带 v_uniq，只有加载更多时才把 315 货架的 feedKey 传回服务端。
    if (page > 1) param.put("v_uniq", JSONArray(uniqueKeys))
    return JSONObject()
        .put("module", "music.recommend.RecommendFeed")
        .put("method", "get_recommend_feed")
        .put("param", param)
}

/** 只沿货架、卡位、卡片协议读取，未知业务卡不会误跳转成歌单。 */
internal fun parseQqMusicFeed(data: JSONObject, hasVipAccess: Boolean): QqMusicFeedPage {
    val shelves = data.optJSONArray("v_shelf") ?: data.optJSONArray("Shelfs")
        ?: throw PlatformApiException("QQ 音乐没有返回音乐流结构")
    val cards = mutableListOf<QqMusicFeedCard>()
    val shelfIds = mutableListOf<String>()
    val uniqueKeys = mutableListOf<String>()
    for (i in 0 until shelves.length()) {
        val shelf = shelves.optJSONObject(i) ?: continue
        val shelfId = shelf.optString("id")
        shelfId.takeIf { it.isNotBlank() }?.let(shelfIds::add)
        val shelfTitle = shelf.qqMusicFeedShelfTitle()
        if (shelfTitle.isQqMusicFeedRemovedSectionTitle()) continue
        val niches = shelf.optJSONArray("v_niche") ?: continue
        val songShelfPages = mutableListOf<List<QqMusicFeedCard.Song>>()
        fun flushSongShelf() {
            if (songShelfPages.isEmpty()) return
            if (shelfTitle.isNotBlank()) {
                cards += QqMusicFeedCard.SongShelf(shelfId, shelfTitle, songShelfPages.toList())
            } else {
                // 标题缺失时保留歌曲内容，不凭空制造一个货架标题。
                cards += songShelfPages.flatten()
            }
            songShelfPages.clear()
        }
        for (j in 0 until niches.length()) {
            val niche = niches.optJSONObject(j) ?: continue
            val values = niche.optJSONArray("v_card") ?: continue
            val section = when (niche.optInt("style")) {
                in QQ_FEED_THREE_ROW_NICHE_STYLES -> QqMusicFeedSection.SONG_RECOMMENDATION
                else -> QqMusicFeedSection.MUSIC_FLOW
            }
            if (section == QqMusicFeedSection.SONG_RECOMMENDATION) {
                val page = valuesSongShelfPage(values, hasVipAccess, section)
                if (page.isNotEmpty()) songShelfPages += page
                // 三行货架只接受官方歌曲卡；节目卡不能降级成歌曲。
                continue
            }
            // 服务端卡位顺序就是音乐流的交错顺序，遇到其他货架前先发布已收集的三行页。
            flushSongShelf()
            for (k in 0 until values.length()) {
                val card = values.optJSONObject(k) ?: continue
                val title = decodeQqPlaylistDescription(card.optString("title"))
                val subtitle = decodeQqPlaylistDescription(card.optString("subtitle"))
                if (title.isQqMusicFeedRemovedSectionTitle()) continue
                val id = card.optString("id")
                val style = card.optInt("style", QQ_FEED_UNKNOWN_STYLE)
                val serverType = card.optInt("type")
                val serverSubtype = card.optInt("subtype")
                // 官方只为 315 号货架回传排重键，扩大范围会让后续分页游标失效。
                if (shelfId == QQ_FEED_UNIQUE_SHELF_ID && card.has("type")) {
                    uniqueKeys += "${serverType}_${serverSubtype}_$id"
                }
                if (card.isQqMusicFeedDailyThirty(style, serverType, serverSubtype, title)) continue
                // 听书节目也是 type=1700/20007 的卡片，不能因为带有普通标题就降级成歌曲。
                if (card.isQqMusicFeedListeningProgram()) continue

                val tracks = card.qqMusicFeedTracks(hasVipAccess, title, subtitle)
                val seedQuery = card.qqMusicFeedSeedSongQuery(style, serverType, serverSubtype, title)
                when {
                    seedQuery != null -> card.toQqMusicFeedSeedSong(seedQuery, title)
                        ?.copy(section = section)?.let(cards::add)
                    card.isQqMusicFeedAlbumPreview(style, serverType, serverSubtype) ->
                        card.toQqMusicFeedAlbumPreviewSong(tracks, title, subtitle)
                            ?.copy(section = section)?.let(cards::add)
                    isQqMusicFeedSongGroup(style, tracks) -> cards += QqMusicFeedCard.SongGroup(
                        recommendationTitle = title.ifBlank { "为你推荐的歌曲" },
                        tracks = tracks.take(QQ_FEED_GROUP_SONG_LIMIT),
                        trackRecommendationTitles = tracks.take(QQ_FEED_GROUP_SONG_LIMIT).map {
                            card.qqMusicFeedRecommendationTitle(title, it.title)
                        },
                        section = section,
                    )
                    card.isQqMusicFeedSong(style, serverType, tracks) -> {
                        val track = tracks.firstOrNull()
                            ?: card.takeIf {
                                serverType == QQ_FEED_SONG_SERVER_TYPE ||
                                    style in QQ_FEED_CARD_LEVEL_SONG_STYLES
                            }
                                ?.toQqMusicFeedCardTrack(style)
                        if (track != null) cards += QqMusicFeedCard.Song(
                            track = track,
                            recommendationTitle = card.qqMusicFeedRecommendationTitle(title, track.title),
                            section = section,
                        )
                    }
                    card.isQqMusicFeedPlaylist(style, serverType, serverSubtype) ->
                        card.toQqMusicFeedPlaylist(title, subtitle)?.copy(section = section)?.let(cards::add)
                    tracks.size == 1 -> cards += QqMusicFeedCard.Song(
                        track = tracks.first(),
                        recommendationTitle = card.qqMusicFeedRecommendationTitle(title, tracks.first().title),
                        section = section,
                    )
                }
            }
        }
        flushSongShelf()
    }
    return QqMusicFeedPage(
        cards = normalizeQqMusicFlowCards(cards),
        shelfIds = shelfIds,
        shelfCount = shelves.length(),
        uniqueKeys = uniqueKeys.distinct().takeLast(QQ_FEED_UNIQUE_KEY_LIMIT),
        loadMark = data.optInt("load_mark", QQ_FEED_UNKNOWN_LOAD_MARK),
    )
}

private fun valuesSongShelfPage(
    values: JSONArray,
    hasVipAccess: Boolean,
    section: QqMusicFeedSection,
): List<QqMusicFeedCard.Song> = buildList {
    for (index in 0 until values.length()) {
        val card = values.optJSONObject(index) ?: continue
        val title = decodeQqPlaylistDescription(card.optString("title"))
        val subtitle = decodeQqPlaylistDescription(card.optString("subtitle"))
        val style = card.optInt("style", QQ_FEED_UNKNOWN_STYLE)
        val serverType = card.optInt("type")
        if (serverType != QQ_FEED_SONG_SERVER_TYPE) continue
        val tracks = card.qqMusicFeedTracks(hasVipAccess, title, subtitle)
        val track = tracks.firstOrNull() ?: card.takeIf {
            serverType == QQ_FEED_SONG_SERVER_TYPE || style in QQ_FEED_CARD_LEVEL_SONG_STYLES
        }?.toQqMusicFeedCardTrack(style) ?: continue
        add(QqMusicFeedCard.Song(
            track = track,
            recommendationTitle = card.qqMusicFeedRecommendationTitle(title, track.title),
            section = section,
        ))
    }
}

private fun JSONObject.qqMusicFeedShelfTitle(): String {
    val template = decodeQqPlaylistDescription(optString("title_template"))
    val content = decodeQqPlaylistDescription(optString("title_content"))
    return template
        .replace("{String}", content)
        .replace("{string}", content)
        .ifBlank { decodeQqPlaylistDescription(optString("title")) }
        .let(::cleanQqFeedRecommendation)
}

/** 当前项目不展示听书节目和 VIP 专属歌曲货架，避免把它们混入音乐流。 */
private fun String.isQqMusicFeedRemovedSectionTitle(): Boolean {
    val normalized = replace(Regex("\\s+"), "")
    return normalized == "今日专属精彩节目" || normalized.startsWith("VIP专属歌曲推荐")
}

private fun JSONObject.isQqMusicFeedListeningProgram(): Boolean =
    (optInt("type") == QQ_FEED_AUDIO_SERVER_TYPE && optInt("jumptype") == QQ_FEED_AUDIO_JUMP_TYPE) ||
        (optInt("type") == QQ_FEED_AUDIO_PLAYLIST_SERVER_TYPE &&
            optInt("subtype") == QQ_FEED_AUDIO_SUBTYPE &&
            optInt("jumptype") == QQ_FEED_AUDIO_PLAYLIST_JUMP_TYPE)

private fun JSONObject.isQqMusicFeedAlbumPreview(
    style: Int,
    serverType: Int,
    serverSubtype: Int,
): Boolean = style == QQ_FEED_ALBUM_PREVIEW_STYLE &&
    serverType == QQ_FEED_ALBUM_PREVIEW_SERVER_TYPE &&
    serverSubtype == QQ_FEED_ALBUM_PREVIEW_SUBTYPE &&
    optInt("jumptype") == QQ_FEED_ALBUM_PREVIEW_JUMP_TYPE

/** 至臻专辑卡是一个可播放的音乐卡，不能把它的三首预览曲当成三歌曲推荐组。 */
private fun JSONObject.toQqMusicFeedAlbumPreviewSong(
    tracks: List<MusicTrack>,
    cardTitle: String,
    cardSubtitle: String,
): QqMusicFeedCard.Song? {
    val miscellany = qqMusicFeedObject("miscellany")
    val themeIds = miscellany?.optString("theme_song_ids").orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank)
    val track = themeIds.firstNotNullOfOrNull { id -> tracks.firstOrNull { it.catalogId == id } }
        ?: tracks.firstOrNull()
        ?: return null
    val artists = track.artists.takeUnless { it.isBlank() || it == "未知歌手" } ?: cardTitle
    val recommendation = listOf("rcmd_reason", "albumRecContent")
        .firstNotNullOfOrNull { field ->
            cleanQqFeedRecommendation(miscellany?.optString(field).orEmpty())
                .takeIf(String::isNotBlank)
        }
    return QqMusicFeedCard.Song(
        track = track.copy(
            artists = artists,
            album = track.album.ifBlank { cardSubtitle },
            artworkUrl = track.artworkUrl ?: optString("cover").takeIf(String::isNotBlank),
        ),
        recommendationTitle = recommendation.orEmpty(),
    )
}

internal fun mergeQqMusicFeed(old: List<QqMusicFeedCard>, new: List<QqMusicFeedCard>) =
    normalizeQqMusicFlowCards(old + new)

private fun JSONObject.toQqMusicFeedPlaylist(title: String, subtitle: String): QqMusicFeedCard.Playlist? {
    val playlistId = optString("id")
    if ((playlistId.toLongOrNull() ?: 0L) <= 0L) return null
    val colors = qqArtworkColors(title)
    val creator = qqMusicFeedPlaylistCreator(subtitle)
    val recommendation = qqMusicFeedPlaylistRecommendation()
    return QqMusicFeedCard.Playlist(MusicPlaylist(
        id = "qq-$playlistId",
        source = MusicSource.QQ,
        title = title.ifBlank { "推荐歌单" },
        // 歌单卡只显示一行副文案，优先保留官方返回的推荐语而不是统计数字。
        subtitle = recommendation ?: creator,
        description = recommendation ?: subtitle,
        count = optInt("cnt"),
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        tracks = emptyList(),
        artworkUrl = optString("cover").takeIf(String::isNotBlank),
    ))
}

private fun JSONObject.qqMusicFeedPlaylistRecommendation(): String? {
    val miscellany = qqMusicFeedObject("miscellany") ?: return null
    return listOf("p_Template_setTitle", "recommendationTitle", "rcmd_reason", "rec_reason")
        .firstNotNullOfOrNull { name ->
            cleanQqFeedRecommendation(miscellany.optString(name)).takeIf(String::isNotBlank)
        }
}

/** 原生音乐流把歌单作者放在 v_user，subtitle 只作为旧协议回退。 */
private fun JSONObject.qqMusicFeedPlaylistCreator(fallback: String): String {
    val users = qqMusicFeedArray("v_user")
    val creator = if (users == null) "" else (0 until users.length()).firstNotNullOfOrNull { index ->
        val user = users.optJSONObject(index) ?: return@firstNotNullOfOrNull null
        listOf("nick", "nickname", "name").firstNotNullOfOrNull { field ->
            decodeQqPlaylistDescription(user.optString(field)).takeIf(String::isNotBlank)
        }
    }.orEmpty()
    return creator.ifBlank { fallback }.ifBlank { "QQ 音乐推荐" }
}

private fun JSONObject.qqMusicFeedTracks(
    hasVipAccess: Boolean,
    cardTitle: String,
    cardSubtitle: String,
): List<MusicTrack> {
    val extra = qqMusicFeedObject("card_extra_info")
    val values = extra?.qqMusicFeedArray("Tracks")?.takeIf { it.length() > 0 }
        ?: extra?.qqMusicFeedArray("SongList")?.takeIf { it.length() > 0 }
        ?: qqMusicFeedObject("track")?.let { JSONArray().put(it) }
        ?: qqMusicFeedObject("Track")?.let { JSONArray().put(it) }
        ?: return emptyList()
    return buildList {
        for (index in 0 until values.length()) {
            val raw = values.optJSONObject(index) ?: continue
            val value = raw.qqMusicFeedObject("track") ?: raw.qqMusicFeedObject("Track") ?: raw
            // 精简歌曲对象使用大写字段，完整歌曲对象沿用公共 QQ 映射器。
            val normalized = if (value.has("MID")) JSONObject()
                .put("mid", value.optString("MID"))
                .put("id", value.optLong("ID"))
                .put("name", value.optString("Name"))
                .put("singer", JSONArray().put(JSONObject().put("name", value.optString("SingerName"))))
                .put("albumname", value.optString("AlbumName"))
                .put("albummid", value.optString("AlbumMID"))
                .put("album", value.optJSONObject("Album") ?: value.optJSONObject("album") ?: JSONObject())
                .put("pay", value.optJSONObject("Pay") ?: value.optJSONObject("pay"))
                .put("action", value.optJSONObject("Action") ?: value.optJSONObject("action"))
            else value
            val track = runCatching { normalized.toQqTrack(hasVipAccess) }.getOrNull()
                ?: value.toQqMusicFeedCompactTrack(cardTitle, cardSubtitle)
                ?: continue
            add(track.copy(
                artworkUrl = value.optString("Cover").takeIf(String::isNotBlank)
                    ?: track.artworkUrl
                    ?: optString("cover").takeIf(String::isNotBlank),
            ))
        }
    }.distinctBy(MusicTrack::id)
}

/** 专辑推荐的三首预览曲只有数字 songId，播放前再由公共解析器补齐 MID 和音质。 */
private fun JSONObject.toQqMusicFeedCompactTrack(cardTitle: String, cardSubtitle: String): MusicTrack? {
    val catalogId = optString("ID").toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
    val title = decodeQqPlaylistDescription(optString("Name")).ifBlank { return null }
    val artist = decodeQqPlaylistDescription(optString("SingerName"))
        .ifBlank { cardTitle.ifBlank { "未知歌手" } }
    val albumObject = optJSONObject("Album") ?: optJSONObject("album")
    val album = decodeQqPlaylistDescription(
        optString("AlbumName").ifBlank { optString("albumname") },
    ).ifBlank { decodeQqPlaylistDescription(albumObject?.optString("name").orEmpty()) }
    val albumMid = optString("AlbumMID").ifBlank { optString("albummid") }
        .ifBlank { albumObject?.optString("mid").orEmpty() }
    val colors = qqArtworkColors(title)
    return MusicTrack(
        qqAccess = qqTrackAccessInfo(false),
        id = "qq-$catalogId",
        source = MusicSource.QQ,
        title = title,
        artists = artist,
        // 卡片 subtitle 常是“某歌手/某专辑来源”的推荐文案，不足以证明歌曲所属专辑。
        // 专辑字段留空，交给播放前的详情补全，避免把三首预览曲误判成同专辑。
        album = album,
        durationMs = 0L,
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        previewUrl = "",
        catalogId = catalogId,
        albumMid = albumMid,
    )
}

/** 官方部分单曲卡只在卡片本身携带歌曲 ID、标题、歌手和封面，不包含 Tracks。 */
private fun JSONObject.toQqMusicFeedCardTrack(style: Int): MusicTrack? {
    val catalogId = when (style) {
        QQ_FEED_WATERFALL_TRACK_STYLE -> optString("subid")
        else -> optString("id")
    }.toLongOrNull()?.takeIf { it > 0L }?.toString()
        ?: listOf("subid", "id").firstNotNullOfOrNull { name ->
            optString(name).toLongOrNull()?.takeIf { it > 0L }?.toString()
        }
        ?: return null
    val title = decodeQqPlaylistDescription(optString("title")).ifBlank { return null }
    val singer = decodeQqPlaylistDescription(optString("subtitle")).ifBlank { "未知歌手" }
    // 20 版信息流里的 subid 可能是模板、视频或其他业务标识，不能当作歌曲 MID。
    // 卡片只信任数字 songId；播放前由详情接口用 songId 补齐真实 media MID。
    val colors = qqArtworkColors(title)
    return MusicTrack(
        id = "qq-$catalogId",
        source = MusicSource.QQ,
        title = title,
        artists = singer,
        album = "",
        durationMs = 0L,
        artworkStart = colors.first,
        artworkEnd = colors.second,
        artworkMark = title.take(1),
        previewUrl = "",
        artworkUrl = optString("cover").takeIf(String::isNotBlank),
        catalogId = catalogId,
        mediaId = "",
    )
}

private fun JSONObject.isQqMusicFeedSong(
    style: Int,
    serverType: Int,
    tracks: List<MusicTrack>,
): Boolean = style != QQ_FEED_SIMILAR_SONG_STYLE && (
    serverType == QQ_FEED_SONG_SERVER_TYPE ||
        (tracks.isNotEmpty() && style in QQ_FEED_SINGLE_SONG_STYLES) ||
        (serverType == 0 && style in QQ_FEED_CARD_LEVEL_SONG_STYLES)
    )

/** 只接受官方已确认的三歌曲卡协议；同专辑预览不在这里直接拼成推荐组。 */
private fun isQqMusicFeedSongGroup(
    style: Int,
    tracks: List<MusicTrack>,
): Boolean {
    if (tracks.size < QQ_FEED_GROUP_SONG_LIMIT) return false
    // style=207/type=400/subtype=414 是官方“至臻专辑”预览，不是三歌曲相似推荐。
    // 真正的三歌曲货架由 style=304 标识，不能按 SongList 数量自行拼卡。
    if (style != QQ_FEED_SIMILAR_SONG_STYLE) return false

    // 精简 SongList 常常暂时没有专辑字段，先保留给仓库补全；无法确认专辑时由仓库丢弃该组。
    return hasQqMusicFeedMixedAlbums(tracks) || tracks.any { it.album.isBlank() && it.albumMid.isBlank() }
}

/** 三歌曲卡必须带完整专辑身份，并且至少来自两张不同专辑。 */
internal fun hasQqMusicFeedMixedAlbums(tracks: List<MusicTrack>): Boolean {
    if (tracks.size < 2) return false
    val albumIdentities = tracks.map { track ->
        track.albumMid.trim().lowercase().takeIf(String::isNotBlank)
            ?: track.album.trim().lowercase().takeIf(String::isNotBlank)
    }
    return albumIdentities.all { it != null } && albumIdentities.distinct().size >= 2
}

private fun JSONObject.isQqMusicFeedPlaylist(style: Int, serverType: Int, serverSubtype: Int): Boolean =
    style == QQ_FEED_PLAYLIST_STYLE ||
        (serverType == QQ_FEED_PLAYLIST_SERVER_TYPE &&
            serverSubtype != QQ_FEED_AI_FOLDER_SUBTYPE &&
            optInt("jumptype") == QQ_FEED_PLAYLIST_JUMP_TYPE) ||
        (serverType == 0 && style in QQ_FEED_FOLDER_STYLES && optInt("jumptype") == QQ_FEED_PLAYLIST_JUMP_TYPE)

private fun JSONObject.isQqMusicFeedDailyThirty(
    style: Int,
    serverType: Int,
    serverSubtype: Int,
    title: String,
): Boolean = style == QQ_FEED_DAILY_30_STYLE ||
    (serverType == QQ_FEED_PLAYLIST_SERVER_TYPE && serverSubtype == QQ_FEED_DAILY_30_SUBTYPE) ||
    title.replace(" ", "").equals("每日30首", ignoreCase = true)

/** AI 歌单中用书名号标出的种子曲按单曲呈现，避免把动态模板 ID 当成普通歌单 ID。 */
private fun JSONObject.qqMusicFeedSeedSongQuery(
    style: Int,
    serverType: Int,
    serverSubtype: Int,
    title: String,
): String? {
    if (style != QQ_FEED_AI_FOLDER_STYLE || serverType != QQ_FEED_PLAYLIST_SERVER_TYPE ||
        serverSubtype != QQ_FEED_AI_FOLDER_SUBTYPE) return null
    return QQ_FEED_SEED_SONG_TITLE.find(title)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
}

private fun JSONObject.toQqMusicFeedSeedSong(query: String, recommendationTitle: String): QqMusicFeedCard.Song? {
    val seedId = optString("id").takeIf(String::isNotBlank) ?: return null
    val colors = qqArtworkColors(query)
    return QqMusicFeedCard.Song(
        track = MusicTrack(
            id = "qq-feed-seed-$seedId",
            source = MusicSource.QQ,
            title = query,
            artists = "",
            album = "",
            durationMs = 0L,
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = query.take(1),
            previewUrl = "",
            artworkUrl = optString("cover").takeIf(String::isNotBlank),
            playable = false,
        ),
        recommendationTitle = cleanQqFeedRecommendation(recommendationTitle),
        lookupQuery = query,
    )
}

private fun JSONObject.qqMusicFeedObject(name: String): JSONObject? = optJSONObject(name)
    ?: optString(name).takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun JSONObject.qqMusicFeedArray(name: String): JSONArray? = optJSONArray(name)
    ?: optString(name).takeIf(String::isNotBlank)?.let { runCatching { JSONArray(it) }.getOrNull() }

/** 官方把正文推荐语放在 TitleLabel 标签中，获奖、评论等其他标签不能混入正文。 */
internal fun JSONObject.qqMusicFeedRecommendationTitle(cardTitle: String, songTitle: String): String {
    val tags = qqMusicFeedArray("tags")
    val titleLabel = (0 until (tags?.length() ?: 0)).firstNotNullOfOrNull { index ->
        val tag = tags?.optJSONObject(index)
        tag?.takeIf { it.optJSONObject("Exts")?.optString("TitleLabel") == "1" }
            ?.optString("Tag")?.takeIf(String::isNotBlank)
    }
    val miscellany = qqMusicFeedObject("miscellany")
    val reason = listOf("rcmd_reason", "recommend_reason", "recommendReason", "reason", "titleTemplate")
        .firstNotNullOfOrNull { name -> miscellany?.optString(name)?.takeIf(String::isNotBlank) }
    return cleanQqFeedRecommendation(titleLabel ?: reason
        ?: cardTitle.takeUnless { it.equals(songTitle, ignoreCase = true) }.orEmpty())
}

internal fun cleanQqFeedRecommendation(value: String): String = decodeQqPlaylistDescription(value)
    .trim().trimEnd('>', '›', '→', ' ').trim('"', '“', '”', ' ')
    .takeUnless { it == "为你推荐" || it.matches(QQ_FEED_NOISE_TEXT) }
    .orEmpty()

private val QQ_FEED_NOISE_TEXT = Regex(
    "(?i)^(?:评论|播放|收听|收藏|喜欢)?\\s*\\d+(?:[.]\\d+)?\\s*(?:万|亿|w|k|m)?\\+?\\s*(?:评论|播放|收听|收藏|喜欢)?$",
)

private const val QQ_FEED_UNKNOWN_STYLE = -1
private const val QQ_FEED_UNKNOWN_LOAD_MARK = -1
private const val QQ_FEED_DAILY_30_STYLE = 7
private const val QQ_FEED_AI_FOLDER_STYLE = 205
private const val QQ_FEED_SINGLE_SONG_RADAR_STYLE = 301
private const val QQ_FEED_PLAYLIST_STYLE = 302
private const val QQ_FEED_SIMILAR_SONG_STYLE = 304
private const val QQ_FEED_ALBUM_PREVIEW_STYLE = 207
private const val QQ_FEED_ALBUM_PREVIEW_SERVER_TYPE = 400
private const val QQ_FEED_ALBUM_PREVIEW_SUBTYPE = 414
private const val QQ_FEED_ALBUM_PREVIEW_JUMP_TYPE = 10002
private val QQ_FEED_THREE_ROW_NICHE_STYLES = setOf(10001, 1014)
private const val QQ_FEED_PLAYLIST_JUMP_TYPE = 10014
private const val QQ_FEED_SONG_SERVER_TYPE = 200
private const val QQ_FEED_PLAYLIST_SERVER_TYPE = 500
private const val QQ_FEED_DAILY_30_SUBTYPE = 510
private const val QQ_FEED_AI_FOLDER_SUBTYPE = 2001
private const val QQ_FEED_AUDIO_SERVER_TYPE = 1700
private const val QQ_FEED_AUDIO_JUMP_TYPE = 20007
private const val QQ_FEED_AUDIO_PLAYLIST_SERVER_TYPE = 400
private const val QQ_FEED_AUDIO_SUBTYPE = 410
private const val QQ_FEED_AUDIO_PLAYLIST_JUMP_TYPE = 10025
private const val QQ_FEED_UNIQUE_SHELF_ID = "315"
private const val QQ_FEED_UNIQUE_KEY_LIMIT = 100
private const val QQ_FEED_GROUP_SONG_LIMIT = 3
private const val QQ_FEED_WATERFALL_TRACK_STYLE = 5105
private val QQ_FEED_SINGLE_SONG_STYLES = setOf(
    10, // NEW_SONG_RECOMMEND_TYPE
    38, // SONG_FEED_ITEM_TYPE
    49, // DETAIL_PAGE_SINGLE_SONG
    58, // V13_SONG_RECOMMEND
    88, // MUSIC_CENTER_SINGLE_SONG
    110, // EDITOR_RECOMMEND_CARD
    208, // V20_SONG_RECOMMEND
    240, // MUSIC_LIST_AI_SINGLE_SONG
    243, // AI_SONG_FEED_ITEM_TYPE
    QQ_FEED_SINGLE_SONG_RADAR_STYLE,
    QQ_FEED_WATERFALL_TRACK_STYLE,
)
private val QQ_FEED_CARD_LEVEL_SONG_STYLES = setOf(
    208,
    QQ_FEED_SINGLE_SONG_RADAR_STYLE,
    QQ_FEED_WATERFALL_TRACK_STYLE,
)
private val QQ_FEED_FOLDER_STYLES = setOf(
    1, 2, 5, // 通用方形歌单
    47, 54, 55, // 详情页、V13 通用/AI 歌单
    84, 93, // 必听歌单、V14 主题歌单
    201, 205, 210, 241, // V20 个性化/AI 歌单
    QQ_FEED_PLAYLIST_STYLE,
)
private val QQ_FEED_SEED_SONG_TITLE = Regex("《([^》]+)》")
