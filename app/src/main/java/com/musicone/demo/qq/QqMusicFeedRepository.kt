package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class QqMusicFeedRepository(context: Context) {
    private val preferences = PlatformPreferences(context)
    private val api = QqApiClient()
    private val artworkAliases = QqArtworkAliasStore(context)

    suspend fun load(page: Int, shelfCount: Int, shelfIds: List<String>, uniqueKeys: List<String>): QqMusicFeedPage = withContext(Dispatchers.IO) {
        val session = preferences.readSession(MusicSource.QQ)
        if (qqCredentialAccountId(session.credential).isBlank() || qqCredentialMusicKey(session.credential).isBlank()) {
            throw PlatformApiException("登录 QQ 音乐后查看音乐流", 301)
        }
        val body = JSONObject().put("comm", qqPlaybackComm(session.credential))
            .put("req_0", qqMusicFeedRequest(page, shelfCount, shelfIds, uniqueKeys))
        val response = JSONObject(PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 14090008(android 14)"),
        ).text)
        val request = response.optJSONObject("req_0")
        val data = request?.optJSONObject("data")
        if (response.optInt("code", -1) != 0 || request?.optInt("code", -1) != 0 || data == null ||
            data.optInt("retcode", 0) != 0) {
            throw PlatformApiException("音乐流加载失败，请稍后重试")
        }
        parseQqMusicFeed(data, session.account?.hasVipAccess == true).forFeedPage(page).resolveSeedSongs(
            credential = session.credential,
            hasVipAccess = session.account?.hasVipAccess == true,
        ).resolveMissingMetadata(
            credential = session.credential,
            hasVipAccess = session.account?.hasVipAccess == true,
        )
    }

    private suspend fun QqMusicFeedPage.resolveSeedSongs(
        credential: String,
        hasVipAccess: Boolean,
    ): QqMusicFeedPage = coroutineScope {
        val limiter = Semaphore(QQ_FEED_SEED_LOOKUP_CONCURRENCY)
        val resolved = cards.asSequence()
            .filterIsInstance<QqMusicFeedCard.Song>()
            .mapNotNull(QqMusicFeedCard.Song::lookupQuery)
            .distinct()
            .map { query ->
                async(Dispatchers.IO) {
                    query to limiter.withPermit {
                        runCatching {
                            api.searchFeedSeedTrack(query, credential, hasVipAccess)
                        }.getOrNull()
                    }
                }
            }
            .toList()
            .awaitAll()
            .toMap()
        val mapped = cards.mapNotNull { card ->
            if (card !is QqMusicFeedCard.Song || card.lookupQuery == null) return@mapNotNull card
            val track = resolved[card.lookupQuery] ?: return@mapNotNull null
            card.copy(
                track = track.copy(artworkUrl = track.artworkUrl ?: card.track.artworkUrl),
                lookupQuery = null,
            )
        }
        copy(cards = mapped.distinctBy(QqMusicFeedCard::key))
    }

    private suspend fun QqMusicFeedPage.resolveMissingMetadata(
        credential: String,
        hasVipAccess: Boolean,
    ): QqMusicFeedPage = coroutineScope {
        val limiter = Semaphore(QQ_FEED_SEED_LOOKUP_CONCURRENCY)
        val originals = cards.flatMap { card -> card.qqMusicFeedTracks() }
            .distinctBy(MusicTrack::id)
            .let { QqTrackAccessResolver().resolve(it, credential) }
        val groupTrackIds = cards.filterIsInstance<QqMusicFeedCard.SongGroup>()
            .flatMap(QqMusicFeedCard.SongGroup::tracks)
            .mapTo(hashSetOf(), MusicTrack::id)
        val resolved = originals.map { original ->
            val aliased = artworkAliases.apply(original)
            async(Dispatchers.IO) {
                // 信息流先发布官方已经返回的标题、歌手和封面；只有没有可用歌曲身份时才提前查详情。
                // 数字 songId 的短卡由播放解析器在点击前补齐 MID、音质和时长，避免刷新为每首歌等待详情请求。
                val canResolveOnPlay = aliased.qqPlaybackMid().isBlank() && aliased.canResolveQqFeedTrackOnPlay()
                // 官方信息流请求主动关闭音质字段；音质必须在播放前通过详情和取票链路确认，
                // 不应让首屏刷新为每一首已经有 MID 的歌曲再发一次详情请求。
                val needsMetadata = original.id in groupTrackIds || (!canResolveOnPlay && (
                    aliased.qqPlaybackMid().isBlank() ||
                        aliased.album.isBlank() ||
                        aliased.durationMs <= 0L ||
                        aliased.artworkUrl.isNullOrBlank()
                    ))
                val complete = if (needsMetadata) limiter.withPermit {
                    runCatching { api.enrichTrackMetadata(aliased, credential, hasVipAccess) }
                        .getOrDefault(aliased)
                } else {
                    aliased
                }
                original.id to complete.withCanonicalQqIdentity()
            }
        }.awaitAll().toMap()
        val resolvedCards = cards.flatMap { card ->
            val resolvedCard = card.resolveQqMusicFeedMetadata(resolved, artworkAliases)
            if (resolvedCard is QqMusicFeedCard.SongGroup) {
                normalizeQqMusicFeedSongGroup(resolvedCard)
            } else {
                listOfNotNull(resolvedCard)
            }
        }
        copy(cards = normalizeQqMusicFlowCards(resolvedCards))
    }

}

/** 信息流短卡通常只有数字 songId；播放入口会复用详情补全和正式取票链路。 */
private fun MusicTrack.canResolveQqFeedTrackOnPlay(): Boolean =
    source == MusicSource.QQ &&
        title.isNotBlank() &&
        catalogId.toLongOrNull()?.let { it > 0L } == true

/**
 * 官方分页会同时返回歌曲推荐货架和音乐流货架；两者由页面分区显示，不能把前者静默丢掉。
 */
internal fun QqMusicFeedPage.forFeedPage(page: Int): QqMusicFeedPage = this

private fun QqMusicFeedCard.qqMusicFeedTracks(): List<MusicTrack> = when (this) {
    is QqMusicFeedCard.Playlist -> emptyList()
    is QqMusicFeedCard.Song -> listOf(track)
    is QqMusicFeedCard.SongGroup -> tracks
    is QqMusicFeedCard.SongShelf -> pages.flatten().map { it.track }
}

private fun QqMusicFeedCard.resolveQqMusicFeedMetadata(
    resolved: Map<String, MusicTrack>,
    artworkAliases: QqArtworkAliasStore,
): QqMusicFeedCard? = when (this) {
    is QqMusicFeedCard.Playlist -> this
    is QqMusicFeedCard.Song -> {
        val ready = resolved[track.id] ?: track
        if (ready.qqPlaybackMid().isBlank() && !ready.canResolveQqFeedTrackOnPlay()) null
        else copy(track = artworkAliases.remember(track, ready))
    }
    is QqMusicFeedCard.SongGroup -> {
        val ready = tracks.mapNotNull { original ->
            val track = resolved[original.id] ?: original
            artworkAliases.remember(original, track).takeIf {
                it.qqPlaybackMid().isNotBlank() || it.canResolveQqFeedTrackOnPlay()
            }
        }
        ready.takeIf { it.isNotEmpty() }?.let { copy(tracks = it) }
    }
    is QqMusicFeedCard.SongShelf -> {
        val pages = pages.mapNotNull { page ->
            page.mapNotNull { song ->
                val track = resolved[song.track.id] ?: song.track
                if (track.qqPlaybackMid().isBlank() && !track.canResolveQqFeedTrackOnPlay()) null
                else song.copy(track = artworkAliases.remember(song.track, track))
            }.takeIf { it.isNotEmpty() }
        }
        pages.takeIf { it.isNotEmpty() }?.let { copy(pages = it) }
    }
}

private const val QQ_FEED_SEED_LOOKUP_CONCURRENCY = 4

/** 只有补齐并确认来自不同专辑时才保留官方三歌曲卡，避免把专辑试听误显示成三连卡。 */
internal fun normalizeQqMusicFeedSongGroup(card: QqMusicFeedCard.SongGroup): List<QqMusicFeedCard> {
    val tracks = card.tracks.take(3)
    if (tracks.size == 3 && hasQqMusicFeedMixedAlbums(tracks)) return listOf(card.copy(tracks = tracks))
    // 同专辑或专辑身份未确认时直接丢弃这个服务端卡位，不能用拆成单曲的方式改变官方内容。
    return emptyList()
}
