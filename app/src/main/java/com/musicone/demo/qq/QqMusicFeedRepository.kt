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
        // 游标分页先接收官方原始卡位；选定显示批次后再补齐必要字段。
        parseQqMusicFeed(data, session.account?.hasVipAccess == true).forFeedPage(page)
    }

    suspend fun prepare(cards: List<QqMusicFeedCard>): List<QqMusicFeedCard> = withContext(Dispatchers.IO) {
        val session = preferences.readSession(MusicSource.QQ)
        QqMusicFeedPage(cards, emptyList(), 0, emptyList(), 0).resolveSeedSongs(
            credential = session.credential,
            hasVipAccess = session.account?.hasVipAccess == true,
        ).resolveMissingMetadata(
            credential = session.credential,
            hasVipAccess = session.account?.hasVipAccess == true,
        ).cards
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
                        try {
                            api.searchFeedSeedTrack(query, credential, hasVipAccess)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
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
            .map(artworkAliases::apply)
        val groupTrackIds = cards.filterIsInstance<QqMusicFeedCard.SongGroup>()
            .flatMap(QqMusicFeedCard.SongGroup::tracks)
            .mapTo(hashSetOf(), MusicTrack::id)
        val missingMetadata = originals.filter { needsQqFeedMetadata(it, it.id in groupTrackIds) }
            .mapTo(hashSetOf(), MusicTrack::id)
        // 横滑推荐和单曲卡不显示权益角标，完整鉴权仍由点播链执行。
        // 三歌曲卡沿用显示前的权益校验，缺失元数据直接复用同一批详情。
        val lookupTracks = originals.filter { it.id in groupTrackIds || it.id in missingMetadata }
        val details = QqTrackAccessResolver().resolve(lookupTracks, credential, metadataTrackIds = missingMetadata)
            .associateBy(MusicTrack::id)
        val resolved = originals.map { original ->
            val ready = details[original.id] ?: original
            async(Dispatchers.IO) {
                // 封面仍沿用已有搜索后备；不为列表展示查询或校验播放音质。
                val complete = if (ready.artworkUrl.isNullOrBlank()) limiter.withPermit {
                    try {
                        api.resolveTrackArtwork(ready, credential, hasVipAccess)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { ready }
                } else ready
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

/** 官方已给出专辑身份时直接验证，缺失时补齐后验证，避免把专辑试听误显示成三连卡。 */
internal fun normalizeQqMusicFeedSongGroup(card: QqMusicFeedCard.SongGroup): List<QqMusicFeedCard> {
    val tracks = card.tracks.take(3)
    if (tracks.size == 3 && hasQqMusicFeedMixedAlbums(tracks)) return listOf(card.copy(tracks = tracks))
    // 同专辑或专辑身份未确认时直接丢弃这个服务端卡位，不能用拆成单曲的方式改变官方内容。
    return emptyList()
}
