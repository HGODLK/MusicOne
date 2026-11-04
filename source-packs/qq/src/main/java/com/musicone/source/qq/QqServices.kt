package com.musicone.source.qq

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.source.qq.internal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class QqServices(private val context: Context, private val auth: QqAuthentication) :
    SourceCatalog, SourceDiscovery, SourceDailyRecommendations, SourceHomeSectionPresentation, SourceHomeContinuation, SourceSearch, SourceCollections, SourceFavorites,
    SourceCollectionEditing, SourceRadio, SourceLyricProvider, SourceTrackTheme, SourceMediaPlayback, SourcePlaybackSessionProvider, SourcePlayback, SourcePlaybackBehavior, SourceLyricBehavior, SourcePlayerPresentation {
    override val missingArtworkHoldMs = 12_000L
    override val waitForSingleSwitchSettlement = true

    private val api = QqApiClient()
    private val listenDuration = QqPlaybackSessionSync(context, auth, api)
    override val playbackSessions: SourcePlaybackSessionObserver = listenDuration
    private val favoriteWrites = QqFavoriteSyncCoordinator()
    private val library = QqLibraryClient()
    private val personalized = QqPersonalizedClient()
    private val daily = QqDailyMixStore(context)
    private val aliases = QqArtworkAliasStore(context)
    private val playlistArtwork = QqPlaylistArtworkAliasResolver(api, aliases)
    private val searchRepository = QqSearchRepository(context)
    private val feed = QqMusicFeedRepository(context)
    private val similarRepository = QqSimilarRecommendationRepository(context)
    private val recentRepository = QqRecentPlayRepository(context)
    private val radioRepository = QqRadioRepository(context)
    private val songRepository = QqPlaylistSongRepository()
    private val homes = QqHomeFeed {
        QqMusicFeedPager(prepare = feed::prepare, fetch = { page, count, shelves, keys ->
            loadQqMusicFeedWithRetry { feed.load(page, count, shelves, keys) }
        })
    }
    private val playlistsSeen = ConcurrentHashMap<String, MusicPlaylist>()
    private val sortPreferences = context.getSharedPreferences("qq_playlist_sort", Context.MODE_PRIVATE)
    val qualityPreferences = context.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)
    override val supportsSimilar = true
    override val supportsRecent = true
    override val supportsSuggestions = true
    override val kinds = setOf(SourceSearchKind.TRACK, SourceSearchKind.COLLECTION, SourceSearchKind.ARTIST, SourceSearchKind.ALBUM)
    override val librarySections = setOf(SourceLibrarySection.FAVORITE_TRACKS, SourceLibrarySection.CREATED_COLLECTIONS, SourceLibrarySection.SAVED_COLLECTIONS)
    override val supportedOperations = setOf(SourceCollectionOperation.CREATE, SourceCollectionOperation.EDIT,
        SourceCollectionOperation.DELETE, SourceCollectionOperation.CHANGE_TRACKS)
    val entityService: SourceEntities = object : SourceEntities, SourceEntityArtwork {
        override suspend fun artwork(reference: SourceEntityRef): SourceArtwork =
            if (reference.kind == "artist") SourceArtwork("https://y.gtimg.cn/music/photo_new/T001R300x300M000${reference.id}.jpg")
            else entity(reference).artwork
        override val kinds = setOf("artist", "album")
        override fun supportsTrackSearch(reference: SourceEntityRef) = reference.kind == "artist"
        override fun trackSortOptions(reference: SourceEntityRef) = if (reference.kind == "artist")
            listOf(SourceCollectionSortOption("popular", "热门歌曲"), SourceCollectionSortOption("latest", "最新歌曲")) else emptyList()
        override suspend fun searchTracks(reference: SourceEntityRef, query: String, sortId: String?, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
            require(reference.kind == "artist")
            val arguments = reference.payload.encoded.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            arguments.put("query", query).put("sort", sortId ?: "popular")
            return this@QqServices.tracks(reference.copy(payload = SourcePayload("qq.artist", 1, arguments.toString())), request)
        }
        override suspend fun entity(reference: SourceEntityRef) = this@QqServices.entity(reference)
        override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest) = this@QqServices.tracks(reference, request)
        override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest) = this@QqServices.collections(reference, request)
    }

    private suspend fun <T> io(work: suspend (PlatformSession) -> T): T = withContext(Dispatchers.IO) {
        try {
            auth.prepareRequest()
            var session = auth.session()
            val result = try { work(session) } catch (error: Exception) {
                if (!error.rejectsQqCredential()) throw error
                // 票可能被服务端提前作废：先续票，再用新票重试一次，续不上才报失效。
                currentCoroutineContext().ensureActive()
                session = auth.renewRejected(session) ?: throw error
                work(session)
            }
            currentCoroutineContext().ensureActive()
            if (!auth.isCurrent(session)) throw CancellationException("QQ账号已变化，旧请求结果已取消")
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw error.toQqSdkFailure() }
    }
    private fun PlatformSession.owner() = account?.userId ?: qqCredentialAccountId(credential)
    private fun PlatformSession.vip() = account?.hasVipAccess == true
    private fun remember(playlist: MusicPlaylist, session: PlatformSession): SourceCollection {
        playlistsSeen[playlist.id] = playlist
        return playlist.collection(session.owner())
    }
    private fun page(values: List<MusicTrack>, request: SourceCursorRequest, session: PlatformSession): SourcePage<SourceTrackDetails> {
        val offset = request.offset()
        val selected = values.drop(offset).take(request.limit.coerceAtLeast(1))
        return SourcePage(selected.map { it.details(session.owner()) }, (offset + selected.size).takeIf { it < values.size }?.toString())
    }

    override suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection> = io { session ->
        val batch = homes.next(request, session.credential)
        SourcePage(batch.items.map { card -> homeSection(card, session) }, batch.nextCursor)
    }

    override fun hasBufferedHomeItems(cursor: String): Boolean = homes.hasBufferedItems(cursor)

    override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> = io { session ->
        val cached = if (policy == SourceFetchPolicy.CACHE_FIRST) daily.read(session.owner()) else null
        val tracks = cached ?: personalized.dailyTracks(session.credential, session.deviceId, session.vip())
            .also { daily.save(session.owner(), it) }
        aliases.remember(QqTrackAccessResolver().resolve(tracks, session.credential, fresh = false))
            .map { it.details(session.owner()) }
    }

    override fun homePresentation(section: SourceHomeSection): SourceHomePresentation {
        val value = section.payload.encoded.takeIf(String::isNotBlank)?.let(::JSONObject)
        val shelf = value?.optString("layout") == "track-shelf"
        val official = shelf && value?.optBoolean("recommendation") == true
        return SourceHomePresentation(section.id, mergePages = shelf, recommendation = official,
            groupId = if (official) "qq-official-recommendations" else null,
            groupLimit = if (official) QQ_OFFICIAL_RECOMMENDATION_LIMIT else null)
    }

    private fun homeSection(card: QqMusicFeedCard, session: PlatformSession): SourceHomeSection = when(card) {
        is QqMusicFeedCard.Playlist -> SourceHomeSection(card.key, card.playlist.title,
            collections = listOf(remember(card.playlist, session)), payload = SourcePayload("musicone.home-section", 1, "{\"layout\":\"collection-card\"}"))
        is QqMusicFeedCard.Song -> SourceHomeSection(card.key, card.recommendationTitle,
            tracks = listOf(card.track.details(session.owner())), payload = SourcePayload("musicone.home-section", 1, "{\"layout\":\"track-card\"}"))
        is QqMusicFeedCard.SongGroup -> SourceHomeSection(card.key, card.recommendationTitle,
            tracks = card.tracks.map { it.details(session.owner()) }, payload = SourcePayload("musicone.home-section", 1,
                JSONObject().put("layout", "track-group").put("recommendationTitles", JSONArray(card.trackRecommendationTitles)).toString()))
        is QqMusicFeedCard.SongShelf -> SourceHomeSection(card.key, card.title,
            tracks = card.pages.flatten().map { it.track.details(session.owner()) }, payload = SourcePayload("musicone.home-section", 1,
                JSONObject().put("layout", "track-shelf").put("recommendation", card.section == QqMusicFeedSection.SONG_RECOMMENDATION)
                    .put("pageSizes", JSONArray(card.pages.map(List<QqMusicFeedCard.Song>::size)))
                    .put("recommendationTitles", JSONArray(card.pages.flatten().map { it.recommendationTitle })).toString()))
    }

    override suspend fun similar(track: SourceTrackDetails, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = io { session ->
        page(similarRepository.load(track.musicTrack(), request.limit).songs.map { it.track }, request, session)
    }
    override suspend fun similarSection(track: SourceTrackDetails, request: SourceCursorRequest): SourceHomeSection = io { session ->
        val result = similarRepository.load(track.musicTrack(), request.limit)
        SourceHomeSection("similar:${track.stableId}", result.title, result.songs.map { it.track.details(session.owner()) })
    }
    override suspend fun recent(request: SourceCursorRequest): SourcePage<SourceTrackDetails> = io { session -> page(recentRepository.load().songs, request, session) }
    override suspend fun recentSnapshot(): SourceRecentSnapshot = io { session ->
        val snapshot = recentRepository.load()
        SourceRecentSnapshot(snapshot.songs.map { it.details(session.owner()) }, snapshot.songCount,
            snapshot.playlists.map { remember(it, session) })
    }
    override suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) {
        if (playedMs <= 0) return
        io { recentRepository.report(track.musicTrack()) }
    }
    override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult = io { session ->
        val tab = when(kind) { SourceSearchKind.TRACK -> QqSearchTab.SONG; SourceSearchKind.COLLECTION -> QqSearchTab.PLAYLIST
            SourceSearchKind.ARTIST -> QqSearchTab.SINGER; SourceSearchKind.ALBUM -> QqSearchTab.ALBUM; else -> error("QQ不支持此搜索类型") }
        val page = request.cursor?.toIntOrNull() ?: 1
        val result = searchRepository.search(query, tab, page)
        SourceSearchResult(result.songs.map { it.details(session.owner()) },
            (result.playlists + result.albums).map { remember(it, session) }, result.singers.map { it.entity() },
            nextCursor = (page + 1).takeIf { result.hasMore }?.toString())
    }
    override suspend fun suggestions(query: String): List<String> = io { searchRepository.suggest(query) }

    private fun artistRepository(session: PlatformSession) = QqArtistRepository(session, aliases)
    private fun album(reference: SourceEntityRef) = MusicPlaylist(QQ_SEARCH_ALBUM_PREFIX + reference.id, MusicSource.QQ,
        reference.name, "", "", 0, 0, 0, reference.name.take(1), emptyList())
    suspend fun entity(reference: SourceEntityRef): SourceEntity = io { session ->
        when(reference.kind) {
            "artist" -> {
                val singer = reference.singer(); val repository = artistRepository(session); val profile = repository.header(singer)
                val trackCount = runCatchingCancellable { repository.songCount(reference.id) }.getOrNull()
                SourceEntity(reference, repository.introduction(singer), SourceArtwork(profile.artwork),
                    buildMap {
                        put("backgrounds", JSONArray(profile.backgrounds).toString())
                        put("numericId", profile.singerId.toString())
                        put("songTabId", profile.songTabId)
                        put("sortOptions", "popular,latest")
                        put("supportsSearch", "true")
                        trackCount?.let { put("trackCount", it.toString()) }
                    })
            }
            "album" -> QqAlbumRepository().load(album(reference), session.credential, session.vip()).let {
                SourceEntity(reference, it.description, SourceArtwork(it.artworkUrl, it.artworkStart, it.artworkEnd, it.artworkMark), mapOf("trackCount" to it.count.toString()))
            }
            else -> throw UnsupportedOperationException("QQ不支持此实体")
        }
    }
    suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = io { session ->
        if (reference.kind == "album") page(QqAlbumRepository().load(album(reference), session.credential, session.vip()).tracks, request, session)
        else {
            require(reference.kind == "artist")
            val arguments = reference.payload.encoded.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            val sort = if (arguments.optString("sort") == "latest") ArtistSongSort.Latest else ArtistSongSort.Popular
            val repository = artistRepository(session)
            if (arguments.optString("query").isNotBlank()) {
                val singer = reference.singer()
                val cursor = request.cursor?.takeIf { it.startsWith("{") }?.let(::JSONObject)
                val excluded = arguments.optJSONArray("excludedSongIds")?.let { array -> (0 until array.length()).map(array::optString) }.orEmpty()
                val values = repository.searchSongs(singer, repository.header(singer), arguments.optString("query"),
                    cursor?.optInt("offset") ?: request.offset(), arguments.optInt("songTotal"), sort, excluded,
                    cursor?.optJSONObject("customInfo") ?: arguments.optJSONObject("customInfo"))
                SourcePage(values.items.map { it.details(session.owner()) }, values.nextOffset?.let {
                    JSONObject().put("offset", it).put("customInfo", values.customInfo).toString()
                })
            } else {
                val values = repository.songs(reference.id, request.offset(), sort)
                SourcePage(values.items.map { it.details(session.owner()) }, values.next?.toString())
            }
        }
    }
    suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> = io { session ->
        require(reference.kind == "artist") { "只有歌手提供专辑列表" }
        val values = artistRepository(session).albums(reference.id, request.offset())
        SourcePage(values.items.map { remember(it, session) }, values.next?.toString())
    }

    override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> = io { session ->
        val owner = session.account?.userId ?: error("请先登录QQ音乐")
        val values = when(section) {
            SourceLibrarySection.FAVORITE_TRACKS -> listOf(library.favoritePlaylistPage(session.credential, owner, session.vip()).playlist)
            SourceLibrarySection.CREATED_COLLECTIONS -> library.createdPlaylists(session.credential, owner)
                .map { it.copy(providerData = it.providerData + ("qqCoverFromLatest" to "true")) }
            SourceLibrarySection.SAVED_COLLECTIONS -> library.collectedPlaylists(session.credential, owner)
        }
        val offset = request.offset(); val selected = values.drop(offset).take(request.limit.coerceAtLeast(1))
        SourcePage(selected.map { remember(it, session) }, (offset + selected.size).takeIf { it < values.size }?.toString())
    }
    private fun playlistPage(playlist: MusicPlaylist, offset: Int, session: PlatformSession): PlaylistTrackPage = when {
        playlist.id == QQ_RECENT_SONGS_PLAYLIST_ID -> PlaylistTrackPage(playlist.copy(tracks = emptyList()))
        playlist.id == QQ_FAVORITES_PLAYLIST_ID -> library.favoritePlaylistPage(session.credential, session.owner(), session.vip(), offset)
        playlist.isQqSearchAlbum -> PlaylistTrackPage(QqAlbumRepository().load(playlist, session.credential, session.vip()))
        playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) -> library.profileDirectoryPage(playlist, session.credential, session.vip(), offset)
        else -> QqPlaylistPaging().page(playlist, session.credential, session.vip(), offset)
    }
    override suspend fun detail(collection: SourceCollection): SourceCollection = io { session ->
        val page = playlistPage(collection.playlist(), 0, session)
        remember(playlistArtwork.resolve(qqPlaylistCoverSource(page.playlist), session.credential), session)
    }
    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = io { session ->
        val playlist = collection.playlist()
        if (playlist.id == QQ_RECENT_SONGS_PLAYLIST_ID) return@io page(recentRepository.load().songs, request, session)
        if (playlist.isQqSearchAlbum) return@io page(playlistPage(playlist, 0, session).playlist.tracks, request, session)
        // 公共歌单页按用户选择统一排序，包内始终返回原始分页，避免重开后倒序两次。
        val result = playlistPage(playlist, request.offset(), session)
        remember(result.playlist, session)
        SourcePage(aliases.remember(result.playlist.tracks).map { it.details(session.owner()) }, result.next?.offset?.toString())
    }

    private fun sortKey(playlist: MusicPlaylist, session: PlatformSession) = qqPlaylistSortKey(playlist, session.owner())
    fun selectedSort(playlist: MusicPlaylist, session: PlatformSession = auth.session()) =
        qqPlaylistSortId(sortKey(playlist, session)?.let { sortPreferences.getString(it, "ADDED_DESC") })
    override fun sortOptions(collection: SourceCollection): List<SourceCollectionSortOption> =
        if (sortKey(collection.playlist(), auth.session()) == null) emptyList() else listOf(
            SourceCollectionSortOption("ADDED_DESC", "加入时间：新到旧"),
            SourceCollectionSortOption("ADDED_ASC", "加入时间：旧到新"),
            SourceCollectionSortOption("NAME", "歌曲名称：升序"))
    override fun selectedSort(collection: SourceCollection): String? =
        if (sortKey(collection.playlist(), auth.session()) == null) null else selectedSort(collection.playlist())
    override fun selectSort(collection: SourceCollection, optionId: String) = setSort(collection.playlist(), optionId)
    fun setSort(playlist: MusicPlaylist, sort: String) {
        require(sort in listOf("ADDED_DESC", "ADDED_ASC", "NAME"))
        val key = sortKey(playlist, auth.session()) ?: error("此歌单不支持账号排序")
        check(sortPreferences.edit().putString(key, sort).commit())
    }
    fun viewedCollections() = playlistsSeen.values.filter { sortKey(it, auth.session()) != null }.sortedBy { it.title }

    override val supportsCollections = true
    override suspend fun favoriteTrackIds(): Set<String> = io { session -> api.likedTrackIds(session.credential, session.owner()) }
    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean = track.musicTrack().isQqFavorite(favoriteTrackIds())
    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) = setTracksFavorite(listOf(track), favorite)
    override suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean) {
        if (tracks.isEmpty()) return
        favoriteWrites.runWrite { io { session ->
            val values = tracks.map { it.musicTrack() }.distinctBy(MusicTrack::id)
            // 推荐页资料可能缺少收藏记录编号，删除前沿用旧版完整收藏映射。
            val resolved = if (favorite) values else {
                val account = requireNotNull(session.account) { "请先登录QQ音乐" }
                val originals = library.favoritePlaylist(session.credential, account.userId, account.hasVipAccess).tracks.associateBy(MusicTrack::id)
                values.map { originals[it.id] ?: it }
            }
            api.setTracksLiked(session.credential, resolved, favorite)
        } }
    }
    override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean) = io { session -> setQqPlaylistCollected(session.credential, collection.playlist(), favorite) }
    override suspend fun writableCollections(): List<SourceCollection> = io { session -> songRepository.targets(session).map { remember(it, session) } }
    private fun editRequest(session: PlatformSession, mode: PlaylistEditMode, playlist: MusicPlaylist?, title: String) {
        val response = JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg",
            qqPlaylistEditRequest(session.credential, mode, playlist, title).toString(), session.credential,
            mapOf("Referer" to "https://y.qq.com/")).text)
        val result = response.optJSONObject("req_0") ?: error("歌单操作返回异常")
        val code = result.optInt("code", -1); val ret = result.optJSONObject("data")?.optInt("retCode", -1) ?: -1
        if (code != 0 || ret != 0) throw PlatformApiException("歌单操作失败（${if(code != 0) code else ret}）")
    }
    private fun owned(collection: SourceCollection, session: PlatformSession): MusicPlaylist = library.createdPlaylists(session.credential, session.owner())
        .firstOrNull { it.id == collection.stableId && !it.isQqFavoritesShortcut() } ?: error("只能修改当前账号自建歌单")
    private fun checkEdit(edit: SourceCollectionEdit) {
        require(edit.title.isNotBlank()) { "请输入歌单名称" }
        require(edit.artworkUri == null) { "QQ现有协议不支持手动上传歌单封面" }
    }
    override suspend fun create(edit: SourceCollectionEdit): SourceCollection = io { session ->
        checkEdit(edit); require(edit.description.isBlank()) { "QQ现有创建接口不支持简介编辑" }
        val before = library.createdPlaylists(session.credential, session.owner()).mapTo(hashSetOf()) { it.id }
        editRequest(session, PlaylistEditMode.Create, null, edit.title.trim())
        val created = library.createdPlaylists(session.credential, session.owner()).firstOrNull { it.id !in before && it.title == edit.title.trim() }
            ?: throw SourceOperationException("created-refresh-required", "歌单已创建，请刷新列表确认", true)
        remember(created, session)
    }
    override suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit): SourceCollection = io { session ->
        checkEdit(edit); require(edit.description.isBlank() || edit.description == collection.description) { "QQ现有编辑接口只支持名称" }
        val target = owned(collection, session)
        editRequest(session, PlaylistEditMode.Rename, target, edit.title.trim())
        remember(target.copy(title = edit.title.trim()), session)
    }
    override suspend fun delete(collection: SourceCollection) = io { session ->
        val target = owned(collection, session); editRequest(session, PlaylistEditMode.Delete, target, target.title); playlistsSeen.remove(target.id); Unit
    }
    override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean) = io { session ->
        songRepository.changeBatch(session, collection.stableId, tracks.map { it.musicTrack() }, add); Unit
    }
    override suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>) {
        throw UnsupportedOperationException("QQ原有排序为加入时间或名称本地排序，不提供远端自由重排")
    }
    override suspend fun next(previous: SourcePayload?): SourceRadioBatch = io { session ->
        val previousIds = if (previous?.format == "qq.radio" && previous.encoded.isNotBlank()) JSONObject(previous.encoded).optJSONArray("ids")?.let { array -> (0 until array.length()).mapTo(hashSetOf()) { array.optString(it) } }.orEmpty() else emptySet()
        val tracks = radioRepository.load(previousIds, 20)
        SourceRadioBatch(tracks.map { it.details(session.owner()) }, SourcePayload("qq.radio", 1, JSONObject().put("ids", JSONArray(previousIds + tracks.map { it.id })).toString()))
    }
    override suspend fun feedback(track: SourceTrackDetails, action: String) {
        when(action) { "like" -> setTrackFavorite(track, true); "unlike" -> setTrackFavorite(track, false); "skip" -> Unit; else -> throw UnsupportedOperationException("QQ原电台不支持此反馈") }
    }
    override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics = io { session -> api.lyrics(track.musicTrack(), session.credential).sdkLyrics() }
    override fun timing(track: SourceTrackDetails) = SourceLyricTiming(275, 105)
    override val parserVersion = 2
    override val refreshPlainLyricsForVerbatim = true
    override val defaultQualityId = "EXHIGH"
    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String =
        when (savedQualityId) { "HIGHER" -> "EXHIGH"; null -> defaultQualityId; else -> savedQualityId.takeIf { id -> AudioQuality.entries.any { it.name == id } } ?: defaultQualityId }
    override fun fallbackQualityIds(preferredId: String): List<String> {
        val normalized = normalizePreferredQuality(preferredId, null)
        val preferred = AudioQuality.valueOf(normalized)
        return QQ_AUDIO_QUALITIES.filter { it.ordinal <= preferred.ordinal &&
            (preferred != AudioQuality.DOLBY || it != AudioQuality.HI_RES) }.asReversed().map { it.name }
    }
    override suspend fun colorArgb(track: SourceTrackDetails): Long = 0xFF31C27C
    private fun preferred(quality: String?): AudioQuality = AudioQuality.entries.firstOrNull { it.name == quality }
        ?: AudioQuality.entries.firstOrNull { it.name == qualityPreferences.getString("preferred_qq", "EXHIGH") } ?: AudioQuality.EXHIGH
    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = qualities(track) {}
    // 每确认一档就回报，菜单不必等最慢的档位探测结束。
    override suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> = io { session ->
        val ready = api.enrichTrackMetadata(track.musicTrack(), session.credential, session.vip())
        api.availableQualities(ready, session.credential) { confirmed -> onProgress(confirmed.map { it.sdkQuality() }) }
            .map { it.sdkQuality() }
    }
    override suspend fun enrich(track: SourceTrackDetails): SourceTrackDetails = io { session ->
        val original = track.musicTrack()
        val ready = if (original.requiresQqPlaybackMetadata())
            api.enrichTrackMetadata(original, session.credential, session.vip()) else original
        ready.details(session.owner())
    }
    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = io { session ->
        val playbackRevision = auth.currentAccount().revision
        val ready = api.enrichTrackMetadata(request.track.musicTrack(), session.credential, session.vip())
        val quality = preferred(request.qualityId)
        val origin = when (request.purpose) {
            SourcePlaybackPurpose.PLAYBACK -> QqRequestOrigin.PLAYBACK
            SourcePlaybackPurpose.PREFETCH -> QqRequestOrigin.PRELOAD
            SourcePlaybackPurpose.CACHE -> QqRequestOrigin.BATCH_CACHE
        }
        val resource = api.playbackSource(ready, session.credential, quality, guid = qqStableGuid(session.deviceId), origin = origin)
        if (request.purpose == SourcePlaybackPurpose.PLAYBACK)
            listenDuration.remember(request.track.musicTrack(), ready, resource, session, playbackRevision)
        SourcePlaybackResource(PlaybackAddress(resource.url, resource.requestHeaders, mimeType = resource.mimeType,
            cacheIdentity = "QQ:${session.owner().ifBlank { "guest" }}|${ready.id}|${android.net.Uri.parse(resource.url).path.orEmpty()}", expiresAtEpochMs = System.currentTimeMillis() + 50 * 60_000L),
            resource.actualQuality.name, resource.actualFormat ?: resource.format,
            recovery = SourcePayload("qq.playback", 1, JSONObject().put("stableId", ready.id).put("qualityId", resource.actualQuality.name).toString()), accountId = session.owner(),
            bitRate = resource.bitRate, actualFormat = resource.actualFormat, trial = resource.trial, verificationPending = resource.verificationPending)
    }
    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolve(request)
    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = io { session ->
        val owner = session.owner()
        val membership = QqEntitlements.membership(session.credential)
        QqTrackAccessResolver().resolve(tracks.map { it.musicTrack() }, session.credential, fresh = false).map { track ->
            val restriction = confirmedQqPlaybackRestriction(track.qqAccess, owner, membership)
            track.details(owner).access.let { access ->
                if (restriction == null) access else access.copy(playable = false, reason = restriction)
            }
        }
    }
    override suspend fun resolve(track: SourceTrack, quality: String?): PlaybackAddress = resolve(SourcePlaybackRequest(
        SourceTrackDetails(track, "qq-${track.identity.remoteId}"), quality)).address
    override suspend fun tracks(page: SourceRoute, cursor: String?): SourcePage<SourceTrack> {
        val request = SourceCursorRequest(cursor)
        val values = when(page.page) {
            "search" -> search(page.arguments["query"].orEmpty(), SourceSearchKind.TRACK, request).let { SourcePage(it.tracks, it.nextCursor) }
            "recent" -> recent(request)
            "daily" -> io { session -> this@QqServices.page(personalized.dailyTracks(session.credential, session.deviceId, session.vip()), request, session) }
            else -> throw UnsupportedOperationException("请使用QQ完整首页或歌单能力")
        }
        return SourcePage(values.items.map { it.track }, values.nextCursor)
    }
    fun close() { homes.clear(); playlistsSeen.clear(); listenDuration.close() }
}
