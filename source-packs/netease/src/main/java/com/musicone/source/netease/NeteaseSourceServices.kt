package com.musicone.source.netease

import com.musicone.source.sdk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal class NeteaseSourceServices(private val state: () -> NeteasePackageState) : SourceDiscovery, SourceDailyRecommendations, SourceSearch,
    SourceCollections, SourceFavorites, SourceCollectionEditing, SourceMediaPlayback, SourceLyricProvider, SourceTrackTheme, SourcePlaybackBehavior, SourceLyricBehavior, SourceCollectionReturnBehavior {
    override val refreshLibraryAfterAnyReturn = true
    override val preserveVerbatimFallback = true
    override fun sortOptions(collection: SourceCollection) = state().collectionSort.options(collection, state().account?.userId)
    override fun selectedSort(collection: SourceCollection) = state().collectionSort.selected(collection, state().account?.userId)
    override fun selectSort(collection: SourceCollection, optionId: String) =
        state().collectionSort.select(collection, state().account?.userId, optionId)

    override val parserVersion = CURRENT_NETEASE_LYRIC_PARSER_VERSION
    override val defaultQualityId = "LOSSLESS"
    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String = when (savedQualityId?.uppercase()) {
        "HIGHER" -> "EXHIGH"
        "DOLBY" -> "LOSSLESS"
        "STANDARD", "EXHIGH", "LOSSLESS", "HI_RES" -> savedQualityId.uppercase()
        else -> defaultQualityId
    }
    override fun fallbackQualityIds(preferredId: String): List<String> {
        val order = listOf("STANDARD", "EXHIGH", "LOSSLESS", "HI_RES")
        return order.take((order.indexOf(normalizePreferredQuality(preferredId, null)).coerceAtLeast(0)) + 1).asReversed()
    }
    private suspend fun <T> read(block: (NeteasePackageState, String, NeteaseAccount?) -> T): T {
        val owner = state(); val revision = owner.revision.get(); val cookie = owner.credential(); val account = owner.account
        val value = try { withContext(Dispatchers.IO) { block(owner, cookie, account) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: NeteaseApiException) {
            throw SourceOperationException(if (error.apiCode == 301) "AUTH_REQUIRED" else "SOURCE_REQUEST_FAILED", error.message ?: "网易云请求失败")
        }
        catch (error: NeteaseSecurityVerificationRequired) { throw SourceOperationException("AUTH_REQUIRED", error.message ?: "请完成官方验证") }
        catch (error: NeteaseProtocolException) { throw SourceOperationException("PLAYBACK_RETRY_REQUIRED", error.message ?: "歌曲暂无可用播放地址", retryable = true) }
        currentCoroutineContext().ensureActive(); owner.check(revision); return value
    }
    override suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection> = read { owner, cookie, account ->
        val page = request.cursor?.toIntOrNull() ?: 0
        val playlists = owner.api.recommendedPlaylists(cookie, page = page).map { it.toCollection(account?.userId) }
        val tracks = owner.api.recommendedTracks(cookie, page, hasVipAccess = account?.hasVipAccess == true).map { it.toDetails(account?.userId) }
        SourcePage(listOf(SourceHomeSection("playlists", "今日推荐", collections = playlists),
            SourceHomeSection("tracks", "推荐歌曲", tracks = tracks)), null)
    }
    override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> = read { owner, cookie, account ->
        owner.api.recommendedTracks(cookie, page = 0, hasVipAccess = account?.hasVipAccess == true).map { it.toDetails(account?.userId) }
    }
    override val kinds = setOf(SourceSearchKind.TRACK)
    override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult {
        require(kind in kinds) { "此音源未提供该搜索类型" }
        return read { owner, cookie, account -> SourceSearchResult(tracks = owner.api.search(query, cookie, account?.hasVipAccess == true).map { it.toDetails(account?.userId) }) }
    }
    override val librarySections = setOf(SourceLibrarySection.CREATED_COLLECTIONS, SourceLibrarySection.SAVED_COLLECTIONS, SourceLibrarySection.FAVORITE_TRACKS)
    override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> = read { owner, cookie, account ->
        val values = owner.api.userPlaylists(cookie, account?.userId.orEmpty()).filter { when (section) {
            SourceLibrarySection.CREATED_COLLECTIONS -> it.isOwned && !it.isPlatformFavorites
            SourceLibrarySection.SAVED_COLLECTIONS -> !it.isOwned && !it.isPlatformFavorites
            SourceLibrarySection.FAVORITE_TRACKS -> it.isPlatformFavorites
        } }
        SourcePage(values.map { it.toCollection(account?.userId) }, null)
    }
    override suspend fun detail(collection: SourceCollection): SourceCollection = read { owner, cookie, account ->
        owner.api.playlistPage(collection.toNetease(), cookie, account?.hasVipAccess == true, null).playlist.toCollection(account?.userId)
    }
    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = read { owner, cookie, account ->
        val cursor = request.cursor?.let { encoded -> JSONObject(encoded).let { value ->
            val ids = value.optJSONArray("ids")
            NeteaseTrackCursor(value.getInt("offset"), ids?.let { (0 until it.length()).map(it::getString) })
        } }
        val page = owner.api.playlistPage(collection.toNetease(), cookie, account?.hasVipAccess == true, cursor)
        val next = page.next?.let { JSONObject().put("offset", it.offset).put("ids", JSONArray(it.trackIds)).toString() }
        SourcePage(page.playlist.tracks.map { it.toDetails(account?.userId) }, next)
    }
    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean = read { owner, cookie, account ->
        track.stableId in owner.api.likedTrackIds(cookie, account?.userId.orEmpty())
    }
    override suspend fun favoriteTrackIds(): Set<String> = read { owner, cookie, account -> owner.api.likedTrackIds(cookie, account?.userId.orEmpty()) }
    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) = read { owner, cookie, account ->
        owner.api.setTrackLiked(cookie, account?.userId.orEmpty(), track.track.identity.remoteId, favorite)
    }
    override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean) { throw UnsupportedOperationException("网易云当前接入未提供收藏歌单操作") }
    override val supportedOperations = setOf(SourceCollectionOperation.CHANGE_TRACKS)
    override suspend fun writableCollections(): List<SourceCollection> = read { owner, cookie, account ->
        owner.api.userPlaylists(cookie, account?.userId.orEmpty()).filter { it.isOwned && !it.isPlatformFavorites }.map { it.toCollection(account?.userId) }
    }
    override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean) {
        require(collection.owned && !collection.accountFavorites) { "只能修改当前账号自己的歌单" }
        for (track in tracks) read { owner, cookie, _ -> owner.api.changePlaylistTrack(cookie, collection.identity.remoteId, track.track.identity.remoteId, add) }
    }
    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = coroutineScope {
        val owner = state(); val revision = owner.revision.get(); val cookie = owner.credential()
        val values = NETEASE_AUDIO_QUALITIES.map { quality -> async(Dispatchers.IO) {
            try {
                val media = owner.api.playbackSource(track.track.identity.remoteId, cookie, quality)
                val verified = verifiedNeteaseQuality(quality, media)
                SourceAudioQuality(quality.name, quality.label, available = verified, reason = if (verified) null else "当前音源未返回完整对应规格")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { SourceAudioQuality(quality.name, quality.label, available = false, reason = error.message ?: "音质探测失败") }
        } }.awaitAll()
        owner.check(revision); values
    }
    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = read { owner, cookie, account ->
        require(request.track.track.identity.sourceId == NETEASE_SOURCE_ID)
        val quality = request.qualityId?.let { name -> NeteaseQuality.entries.firstOrNull { it.name.equals(name, true) || it.providerLevel == name } }
            ?: preferredQuality(owner)
        val media = owner.api.playbackSource(request.track.track.identity.remoteId, cookie, quality)
        SourcePlaybackResource(PlaybackAddress(media.url, media.requestHeaders, mimeType = when (media.format.lowercase()) {
            "flac" -> "audio/flac"; "aac", "m4a" -> "audio/mp4"; else -> "audio/mpeg"
        }, expiresAtEpochMs = System.currentTimeMillis() + 18 * 60_000L, cacheIdentity = "NETEASE:${account?.userId ?: "guest"}|${request.track.stableId}|${android.net.Uri.parse(media.url).path.orEmpty()}"),
            media.actualQuality.name, media.format, recovery = SourcePayload("netease.playback", 1, request.track.track.identity.remoteId),
            accountId = account?.userId, bitRate = media.bitRate, actualFormat = media.actualFormat, trial = media.trial,
            verificationPending = media.verificationPending)
    }
    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolve(request)
    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = read { owner, cookie, account ->
        val values = owner.api.songDetails(tracks.map { it.track.identity.remoteId }, cookie, account?.hasVipAccess == true).associateBy { it.id }
        tracks.map { values[it.stableId]?.toDetails(account?.userId)?.access ?: it.access }
    }
    override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics = read { owner, cookie, _ -> owner.api.lyrics(track.track.identity.remoteId, cookie).toSourceLyrics() }
    // 565 毫秒替代旧的 380 毫秒，逐字时间线保持独立，不在原值上叠加。
    override fun timing(track: SourceTrackDetails) = SourceLyricTiming(565, 0)
    override suspend fun colorArgb(track: SourceTrackDetails): Long = NETEASE_PAGE_COLOR
    private fun preferredQuality(owner: NeteasePackageState): NeteaseQuality {
        val name = owner.environment.context.getSharedPreferences("netease_settings", 0).getString("quality", NeteaseQuality.LOSSLESS.name)
        return NETEASE_AUDIO_QUALITIES.firstOrNull { it.name == name } ?: NeteaseQuality.LOSSLESS
    }
}
