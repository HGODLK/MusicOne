package com.musicone

import android.content.Context
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toMusicTrack
import com.musicone.sourceintegration.toMusicPlaylist
import com.musicone.sourceintegration.toSourceTrack
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 远端私有引用只存本机，模型只能看到公开摘要及随机句柄。 */
internal class AiSourceTools(context: Context, private val libraryTools: AiLibraryTools, private val checkpoint: JSONObject) {
    private val controller = SourcePackageController.get(context.applicationContext)
    private val platforms = PlatformPreferences(context.applicationContext)
    private val refs = context.applicationContext.getSharedPreferences("musicone_ai_source_refs", Context.MODE_PRIVATE)

    /** 查询按本轮首次搜索时的主音源路由，切换界面音源不使在途请求失效。 */
    private fun searchMainId(): String = aiSearchMainSource(checkpoint) { platforms.selectedSource().id }

    fun validateTracks(tracks: List<MusicTrack>) {
        tracks.filter { it.source != MusicSource.LOCAL }.groupBy { it.source.id }.forEach { (sourceId, items) ->
            val instance = controller.running(sourceId) ?: error("歌曲所属音源未启用")
            val account = instance.entry.authentication?.currentAccount()?.account?.id
            check(items.all { it.sourceAccountId == null || it.sourceAccountId == account }) { "歌曲所属账号已变化，请重新读取" }
        }
    }

    /** 解析模型选源不触发所有音源的会员请求，sourceId自身不能产生用户授权。 */
    suspend fun requestedSearchSource(id: String?, cursor: String?): String? {
        controller.awaitStartup()
        val scope = searchScope()
        val selected = aiRequestedSearchSource(scope, id, cursor)
        return if (cursor == null && (scope.allowFallback || scope.sourceIds.size > 1)) null else selected
    }

    private fun searchScope(): AiSearchScope {
        val available = SourceKind.entries.flatMap(controller::available)
        val names = mutableMapOf<String, MutableList<String>>()
        fun add(id: String, values: List<String>) { names.getOrPut(id) { mutableListOf() }.addAll(values) }
        MusicSource.entries.forEach { add(it.id, listOf(it.id, it.name, it.label)) }
        controller.installed.value.forEach { add(it.id, listOfNotNull(it.id, it.name)) }
        available.forEach { add(it.entry.descriptor.id, listOf(it.entry.descriptor.id, it.entry.descriptor.name)) }
        return aiUpdateSearchScope(checkpoint, names, available.mapTo(hashSetOf()) { it.entry.descriptor.id }, searchMainId())
    }

    suspend fun sources(): JSONObject = withContext(Dispatchers.IO) {
        controller.awaitStartup()
        val mainId = searchMainId()
        JSONObject().put("mainSourceId", mainId).put("sources", JSONArray(SourceKind.entries.flatMap(controller::available).map { instance ->
            val entry = instance.entry
            val state = entry.authentication?.currentAccount()
            val membership = membershipForSearch(instance)
            verify(instance, state)
            val tier = tier(instance, membership)
            JSONObject().put("sourceId", entry.descriptor.id).put("name", entry.descriptor.name)
                .put("isMainSource", entry.descriptor.id == mainId).put("membership", aiMembershipStatus(entry.membershipDetectorOrNull() != null, membership))
                .put("kind", entry.descriptor.kind.name).put("membershipDetectorDeclared", entry.membershipDetectorOrNull() != null)
                .put("searchEligible", tier != null).put("searchPriority", tier ?: JSONObject.NULL)
                .put("requiresCrossSourceAuthorization", false)
                .put("eligibilityReason", eligibilityReason(instance, membership))
                .put("sections", JSONArray(entry.collections?.librarySections.orEmpty().map { it.name }))
                .put("supplementalCollections", entry.remoteCollections != null)
                .put("supplementalContent", entry.importing != null || entry.contents != null)
                .put("searchTracks", entry.search?.kinds?.contains(SourceSearchKind.TRACK) == true)
                .put("searchPlaylists", entry.search?.kinds?.contains(SourceSearchKind.COLLECTION) == true)
                .put("account", state?.account?.id ?: "unknown")
                .put("loggedIn", state?.status == SourceSessionStatus.AUTHENTICATED)
        })).put("complete", true)
    }

    suspend fun remotePlaylists(sourceId: String, section: String, cursor: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val source = source(sourceId)
        val account = source.entry.authentication?.currentAccount()
        val stamp = stamp(source)
        val service = requireNotNull(source.entry.collections) { "此音源不支持歌单目录" }
        val kind = SourceLibrarySection.valueOf(section)
        require(kind in service.librarySections) { "此音源不支持该资料库分区" }
        val pageCursor = cursor(cursor, source, "directory:$section")
        verify(source, account)
        val page = source.withOperation { service.library(kind, SourceCursorRequest(pageCursor, 50)) }
        verify(source, account)
        val items = JSONArray(page.items.map { collection ->
            require(collection.identity.sourceId == sourceId) { "音源返回了不一致的歌单身份" }
            val ref = save(JSONObject().put("kind", "playlist").put("stamp", stamp)
                .put("sourceId", sourceId).put("section", section).put("collection", encode(collection)))
            JSONObject().put("playlistRef", ref).put("title", collection.title)
                .put("count", collection.count ?: JSONObject.NULL).put("sourceId", sourceId)
                .put("source", source.entry.descriptor.name).put("account", collection.identity.accountId ?: "unknown")
                .put("creator", "unknown").put("owned", collection.owned)
                .put("type", when (kind) {
                    SourceLibrarySection.CREATED_COLLECTIONS -> "remote_created"
                    SourceLibrarySection.SAVED_COLLECTIONS -> "remote_saved"
                    else -> "remote_favorites"
                }).put("section", section).put("canEditMembers", false).put("canDelete", false)
        })
        JSONObject().put("items", items).put("scope", "page").put("pageComplete", true)
            .put("isLastPage", page.nextCursor == null).put("complete", cursor == null && page.nextCursor == null)
            .put("cursor", next(source, "directory:$section", page.nextCursor))
    }

    suspend fun remoteTracks(playlistRef: String, cursor: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val reference = load(playlistRef)
        require(reference.optString("kind") == "playlist") { "歌单引用无效" }
        val source = source(reference.getString("sourceId"))
        require(reference.getString("stamp") == stamp(source)) { "音源或账号已变化，请重新读取目录" }
        val account = source.entry.authentication?.currentAccount()
        val collection = decode(reference.getJSONObject("collection"))
        validateCollection(source, account, collection)
        val service = requireNotNull(source.entry.collections) { "此音源不支持歌单" }
        val pageCursor = cursor(cursor, source, "tracks:$playlistRef")
        verify(source, account)
        val page = source.withOperation { service.tracks(collection, SourceCursorRequest(pageCursor, 50)) }
        verify(source, account)
        val tracks = page.items.map { item ->
            require(item.track.identity.sourceId == source.entry.descriptor.id) { "音源返回了不一致的歌曲身份" }
            require(item.track.identity.accountId == null || item.track.identity.accountId == account?.account?.id) { "音源返回了不一致的歌曲账号" }
            item.toMusicTrack()
        }
        val complete = cursor == null && page.nextCursor == null
        val identity = JSONObject().put("title", collection.title).put("sourceId", collection.identity.sourceId)
            .put("source", source.entry.descriptor.name).put("account", collection.identity.accountId ?: "unknown")
            .put("owned", collection.owned).put("creator", "unknown")
            .put("type", when (reference.optString("section")) {
                "CREATED_COLLECTIONS" -> "remote_created"
                "SAVED_COLLECTIONS" -> "remote_saved"
                "FAVORITE_TRACKS" -> "remote_favorites"
                else -> "remote_collection"
            }).put("canEditMembers", false).put("canDelete", false)
        val nextCursor = next(source, "tracks:$playlistRef", page.nextCursor)
        val pagination = JSONObject().put("collectionKey", source.entry.descriptor.id + ":" + stamp(source) + ":" + playlistRef)
            .put("playlistRef", playlistRef).put("inputCursor", cursor ?: "").put("nextCursor", nextCursor)
        val registered = libraryTools.registerResult(tracks, collection.title, complete, collection.count ?: tracks.size, identity, pagination = pagination)
        libraryTools.readResult(registered.getString("handle"))
            .put("unknownTotalCount", collection.count == null)

    }

    suspend fun searchTracks(query: String, sourceId: String? = null, cursor: String? = null, allowOtherSource: Boolean = false, request: String = ""): JSONObject = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索词不能为空" }
        controller.awaitStartup()
        if (sourceId == null && cursor == null) return@withContext routeSearch(query, false, request)
        val mainId = searchMainId()
        val selected = requireNotNull(sourceId)
        val source = searchSource(selected)
        checkpoint.put("lastSearchAttempt", JSONObject().put("sourceId", selected).put("source", source.entry.descriptor.name).put("searchInvoked", false))
        requireSearchScope(source)
        val account = source.entry.authentication?.currentAccount()
        val explicitlyRequested = explicitSearchAuthorization(source, cursor, "search:$query")
        val membershipSnapshot = if (explicitlyRequested) source.entry.currentMembership() else membershipForSearch(source)
        verify(source, account)
        require(explicitlyRequested || tier(source, membershipSnapshot) != null) { "该音源不符合会员搜索资格" }
        val service = requireNotNull(source.entry.search) { "此音源不支持搜索" }
        require(SourceSearchKind.TRACK in service.kinds) { "此音源不支持歌曲搜索" }
        val purpose = "search:$query"
        val pageCursor = cursor(cursor, source, purpose)
        verify(source, account)
        val page = source.withOperation {
            checkpoint.put("lastSearchAttempt", JSONObject().put("sourceId", selected).put("source", source.entry.descriptor.name).put("searchInvoked", true))
            service.search(query, SourceSearchKind.TRACK, SourceCursorRequest(pageCursor, 50))
        }
        verify(source, account)
        val tracks = page.tracks.map { item ->
            require(item.track.identity.sourceId == selected) { "音源返回了不一致的歌曲身份" }
            require(item.track.identity.accountId == null || item.track.identity.accountId == account?.account?.id) { "音源返回了不一致的歌曲账号" }
            item.toMusicTrack()
        }
        val complete = cursor == null && page.nextCursor == null
        val registered = libraryTools.registerResult(tracks, "远端搜索：$query", complete)
        verify(source, account)
        libraryTools.readResult(registered.getString("handle")).also {
            verify(source, account)
        }.put("scope", "page").put("pageComplete", true)
            .put("sourceId", selected).put("mainSourceId", mainId)
            .put("membership", aiMembershipStatus(source.entry.membershipDetectorOrNull() != null, membershipSnapshot))
            .put("searchPriority", tier(source, membershipSnapshot) ?: JSONObject.NULL).put("source", source.entry.descriptor.name)
            .put("explicitSearchAuthorization", explicitlyRequested)
            .put("searchInvoked", true).put("status", if (tracks.isEmpty()) "empty" else "ok")
            .put("eligibilityReason", if (explicitlyRequested) "用户原话明确指定音源搜索，播放权益仍须检查" else eligibilityReason(source, membershipSnapshot))
            .put("message", if (tracks.isEmpty()) "该音源未找到歌曲" else "已返回该音源搜索结果")
            .put("collectionComplete", complete).put("isLastPage", page.nextCursor == null)
            .put("unknownTotalCount", true).put("totalCount", JSONObject.NULL)
            .put("nextCursor", next(source, purpose, page.nextCursor, explicitlyRequested))
    }

    suspend fun searchPlaylists(query: String, sourceId: String? = null, cursor: String? = null, allowOtherSource: Boolean = false, request: String = ""): JSONObject = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索词不能为空" }
        controller.awaitStartup()
        if (sourceId == null && cursor == null) return@withContext routeSearch(query, true, request)
        val mainId = searchMainId()
        val source = searchSource(requireNotNull(sourceId))
        checkpoint.put("lastSearchAttempt", JSONObject().put("sourceId", sourceId).put("source", source.entry.descriptor.name).put("searchInvoked", false))
        requireSearchScope(source)
        val account = source.entry.authentication?.currentAccount()
        val explicitlyRequested = explicitSearchAuthorization(source, cursor, "search-playlists:$query")
        val membershipSnapshot = if (explicitlyRequested) source.entry.currentMembership() else membershipForSearch(source)
        verify(source, account)
        require(explicitlyRequested || tier(source, membershipSnapshot) != null) { "该音源不符合会员搜索资格" }
        val service = requireNotNull(source.entry.search) { "此音源不支持搜索" }
        require(SourceSearchKind.COLLECTION in service.kinds) { "此音源未声明真实歌单搜索能力" }
        val purpose = "search-playlists:$query"
        val pageCursor = cursor(cursor, source, purpose)
        verify(source, account)
        val page = source.withOperation {
            checkpoint.put("lastSearchAttempt", JSONObject().put("sourceId", sourceId).put("source", source.entry.descriptor.name).put("searchInvoked", true))
            service.search(query, SourceSearchKind.COLLECTION, SourceCursorRequest(pageCursor, 50))
        }
        verify(source, account)
        val items = page.collections.map { collection ->
            validateCollection(source, account, collection)
            val ref = save(JSONObject().put("kind", "playlist").put("stamp", stamp(source))
                .put("sourceId", source.entry.descriptor.id).put("collection", encode(collection)))
            JSONObject().put("playlistRef", ref).put("title", collection.title).put("subtitle", collection.subtitle)
                .put("count", collection.count ?: JSONObject.NULL).put("sourceId", collection.identity.sourceId)
                .put("source", source.entry.descriptor.name).put("account", collection.identity.accountId ?: "unknown")
                .put("owned", collection.owned).put("creator", "unknown").put("type", "remote_collection")
        }
        JSONObject().put("items", JSONArray(items)).put("sourceId", source.entry.descriptor.id)
            .put("mainSourceId", mainId).put("membership", aiMembershipStatus(source.entry.membershipDetectorOrNull() != null, membershipSnapshot))
            .put("source", source.entry.descriptor.name).put("searchPriority", tier(source, membershipSnapshot) ?: JSONObject.NULL)
            .put("explicitSearchAuthorization", explicitlyRequested)
            .put("searchInvoked", true).put("status", if (items.isEmpty()) "empty" else "ok").put("count", items.size)
            .put("eligibilityReason", if (explicitlyRequested) "用户原话明确指定音源搜索，播放权益仍须检查" else eligibilityReason(source, membershipSnapshot)).put("isLastPage", page.nextCursor == null)
            .put("complete", cursor == null && page.nextCursor == null).put("nextCursor", next(source, purpose, page.nextCursor, explicitlyRequested))
            .put("message", if (items.isEmpty()) "该音源未找到歌单" else "已返回音源真实歌单")
    }

    /** 原生歌单卡和播放共用真实歌单身份；逐页读取，不伪造搜索结果歌单。 */
    suspend fun resolvePlaylist(playlistRef: String): MusicPlaylist = withContext(Dispatchers.IO) {
        val reference = load(playlistRef)
        require(reference.optString("kind") == "playlist") { "歌单引用无效" }
        val source = source(reference.getString("sourceId"))
        require(reference.getString("stamp") == stamp(source)) { "音源或账号已变化，请重新读取目录" }
        val account = source.entry.authentication?.currentAccount()
        val collection = decode(reference.getJSONObject("collection"))
        validateCollection(source, account, collection)
        val service = requireNotNull(source.entry.collections) { "此音源不支持歌单" }
        val tracks = mutableListOf<MusicTrack>()
        val cursors = hashSetOf<String>()
        var pageCursor: String? = null
        do {
            verify(source, account)
            val page = source.withOperation { service.tracks(collection, SourceCursorRequest(pageCursor, 100)) }
            verify(source, account)
            tracks += page.items.map { item ->
                require(item.track.identity.sourceId == collection.identity.sourceId &&
                    (item.track.identity.accountId == null || item.track.identity.accountId == account?.account?.id)) { "音源返回了不一致的歌曲身份" }
                item.toMusicTrack()
            }
            pageCursor = page.nextCursor
            check(pageCursor?.let(cursors::add) != false) { "音源返回重复分页，歌单读取未完成" }
        } while (pageCursor != null)
        collection.toMusicPlaylist(tracks.distinctBy(MusicTrack::id))
    }

    /** 只调用音源已有的批量权益检查，不进入播放器，不重复试播。 */
    suspend fun validatePlayable(tracks: List<MusicTrack>): AiPlaybackValidation = withContext(Dispatchers.IO) {
        controller.awaitStartup()
        val accepted = mutableMapOf<String, MusicTrack>()
        val skipped = mutableListOf<JSONObject>()
        val unknown = mutableListOf<JSONObject>()
        tracks.filter { it.source == MusicSource.LOCAL }.forEach { track ->
            if (track.playable) accepted[track.id] = track else skipped += aiPlaybackIssue(track, "unavailable", track.unavailableReason ?: "此歌曲不可播放")
        }
        tracks.filter { it.source != MusicSource.LOCAL }.groupBy { it.source.id }.forEach { (id, group) ->
            val instance = controller.running(id)
            if (instance == null) {
                unknown += group.map { aiPlaybackIssue(it, "unknown", "歌曲所属音源未启用，未播放") }
                return@forEach
            }
            val account = instance.entry.authentication?.currentAccount()
            validateTracks(group)
            verify(instance, account)
            val availability = try {
                instance.entry.mediaPlayback?.let { playback -> instance.withOperation { playback.availability(group.map { it.toSourceTrack() }) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            verify(instance, account)
            if (availability == null || availability.size != group.size) {
                unknown += group.map { aiPlaybackIssue(it, "unknown", "音源未能确认播放权限，已跳过且未试播") }
            } else group.zip(availability).forEach { (track, access) ->
                if (access.playable && !access.trialAvailable) {
                    accepted[track.id] = track.toSourceTrack().copy(access = access).toMusicTrack()
                } else skipped += aiPlaybackIssue(track, if (access.trialAvailable) "trial_available" else "unavailable",
                    access.reason ?: if (access.trialAvailable) "音源仍声明试听可用，未确认完整播放权限，已跳过" else "此歌曲不可播放，已跳过")
            }
        }
        AiPlaybackValidation(tracks.distinctBy(MusicTrack::id).mapNotNull { accepted[it.id] }, skipped, unknown)
    }

    private suspend fun membership(source: SourceInstance): Boolean? {
        val account = source.entry.authentication?.currentAccount()
        verify(source, account)
        val value = source.withOperation { source.entry.detectMembership() }
        verify(source, account)
        return value
    }

    private suspend fun membershipForSearch(source: SourceInstance): Boolean? =
        if (source.entry.authentication == null) null else membership(source)

    private fun tier(source: SourceInstance, membership: Boolean?) = aiSearchTier(source.entry.descriptor.kind,
        source.entry.descriptor.id == searchMainId(),
        source.entry.membershipDetectorOrNull() != null, membership,
        authenticationDeclared = source.entry.authentication != null)

    private fun eligibilityReason(source: SourceInstance, membership: Boolean?): String = when {
        membership == true -> "音源已确认音乐会员"
        source.entry.authentication == null -> "音源未声明登录要求，开放搜索可用"
        source.entry.descriptor.kind == SourceKind.SUPPLEMENTAL && source.entry.membershipDetectorOrNull() == null -> "补充源未启用会员要求"
        source.entry.descriptor.id == searchMainId() -> if (source.entry.membershipDetectorOrNull() == null)
            "当前主音源未启用会员检测，使用时须告知用户" else "当前主音源未确认会员，使用时须告知用户；未确认不等于确定无会员"
        else -> "未确认会员，不参与搜索"
    }

    private suspend fun searchSource(id: String): SourceInstance = source(id)

    /** 一级无结果后才进入并列二级；每个音源的后续游标仍绑定原实例与账号。 */
    private suspend fun routeSearch(query: String, playlists: Boolean, request: String): JSONObject {
        val main = searchMainId()
        val candidates = mutableListOf<Pair<SourceInstance, Int>>()
        val available = SourceKind.entries.flatMap(controller::available)
        val scope = searchScope()
        val explicit = scope.sourceIds
        available.forEach { instance ->
            val id = instance.entry.descriptor.id
            if (id in scope.excludedIds || scope.currentOnly && id != main) return@forEach
            if (explicit.isNotEmpty() && id !in explicit && !scope.allowFallback) return@forEach
            val kind = if (playlists) SourceSearchKind.COLLECTION else SourceSearchKind.TRACK
            if (instance.entry.search?.kinds?.contains(kind) != true) {
                require(id !in explicit) { "指定音源不支持${if (playlists) "歌单" else "歌曲"}搜索：${instance.entry.descriptor.name}" }
                return@forEach
            }
            val priority = aiSearchPriority(scope, id, main) { tier(instance, membershipForSearch(instance)) }
            priority?.let { candidates += instance to it }
        }
        val results = mutableListOf<JSONObject>()
        for (priority in 0..2) {
            for ((instance, _) in candidates.filter { it.second == priority }) {
                val account = instance.entry.authentication?.currentAccount()
                verify(instance, account)
                checkpoint.remove("lastSearchAttempt")
                val result = try {
                    var pageCursor: String? = null
                    val visited = hashSetOf<String>()
                    var page: JSONObject
                    do {
                        val args = JSONObject().put("query", query).put("sourceId", instance.entry.descriptor.id).put("cursor", pageCursor)
                        page = AiToolRetry(checkpoint).execute(if (playlists) "route_search_playlists" else "route_search_tracks",
                            args.toString(), cacheSuccess = true) {
                            verify(instance, account)
                            if (playlists) searchPlaylists(query, instance.entry.descriptor.id, pageCursor, request = request)
                            else searchTracks(query, instance.entry.descriptor.id, pageCursor, request = request)
                        }
                        if (aiToolResultFailed(page)) {
                            page.put("sourceId", instance.entry.descriptor.id).put("source", instance.entry.descriptor.name)
                            break
                        }
                        pageCursor = page.optional("nextCursor")
                        check(pageCursor == null || visited.add(load(pageCursor).getString("cursor"))) { "音源返回重复搜索游标" }
                    } while (page.optJSONArray("items")?.length() == 0 && pageCursor != null)
                    page
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    verify(instance, account)
                    JSONObject().put("sourceId", instance.entry.descriptor.id).put("source", instance.entry.descriptor.name)
                        .put("status", "failed").put("searchInvoked", checkpoint.optJSONObject("lastSearchAttempt")?.optBoolean("searchInvoked") == true)
                        .put("message", error.message ?: "搜索失败")
                }
                verify(instance, account)
                results += result
            }
            if (results.any { if (playlists) it.optJSONArray("items")?.length()?.let { count -> count > 0 } == true else it.optInt("count") > 0 }) break
        }
        val count = results.sumOf { if (playlists) it.optJSONArray("items")?.length() ?: 0 else it.optInt("count") }
        return JSONObject().put("sourceResults", JSONArray(results)).put("requestedSourceIds", JSONArray(explicit.toList()))
            .put("count", count).put("searchInvoked", results.any { it.optBoolean("searchInvoked") })
            .put("status", if (count > 0) "ok" else if (results.any { aiToolResultFailed(it) }) "failed" else "empty")
            .put("retriesExhausted", count == 0 && results.any { it.optBoolean("retriesExhausted") })
            .put("retryCount", if (results.any { it.optBoolean("retriesExhausted") }) 3 else 0)
            .put("routing", if (explicit.isEmpty()) "一级当前会员主音源优先，无结果后二级并列音源" else if (scope.allowFallback) "先搜索用户指定音源，无结果后按用户许可搜索其他合资格音源" else "仅搜索用户明确指定的音源，不自动换源")
            .put("hasMore", results.any { it.optional("nextCursor") != null }).put("message", if (results.isEmpty()) "没有符合搜索资格的音源" else "所用音源及会员资格见各sourceResults；后续使用对应sourceId和nextCursor分页")
    }

    /** 即使模型复用旧分页，也不能离开本轮用户明确限定的音源。 */
    private fun requireSearchScope(source: SourceInstance) {
        val scope = searchScope()
        val id = source.entry.descriptor.id
        require(id !in scope.excludedIds && (!scope.currentOnly || id == searchMainId())) { "用户已排除此音源" }
        require(scope.sourceIds.isEmpty() || id in scope.sourceIds || scope.allowFallback) { "用户已明确指定其他音源，请按指定音源搜索" }
    }

    /** 明确授权只来自用户原话或已绑定音源、查询及账号的宿主分页引用。 */
    private fun explicitSearchAuthorization(source: SourceInstance, ref: String?, purpose: String): Boolean {
        if (source.entry.descriptor.id in searchScope().sourceIds) return true
        if (ref.isNullOrBlank()) return false
        cursor(ref, source, purpose)
        return load(ref).optBoolean("explicitSearch")
    }

    private fun validateCollection(source: SourceInstance, account: SourceAccountState?, collection: SourceCollection) {
        require(collection.identity.sourceId == source.entry.descriptor.id &&
            (collection.identity.accountId == null || collection.identity.accountId == account?.account?.id)) { "音源返回了不一致的歌单身份" }
    }

    private suspend fun source(id: String): SourceInstance {
        controller.awaitStartup()
        val source = controller.running(id) ?: error("音源未启用")
        return source
    }

    private fun verify(source: SourceInstance, account: SourceAccountState?) {
        check(controller.running(source.entry.descriptor.id) === source) { "音源实例已变化，请重新读取" }
        check(aiSameSourceSession(account, source.entry.authentication?.currentAccount())) { "账号已变化，请重新读取" }
    }

    private fun stamp(source: SourceInstance): String {
        val entry = source.entry
        val account = entry.authentication?.currentAccount()
        return "${entry.descriptor.id}:${entry.descriptor.versionCode}:${account?.account?.id.orEmpty()}:${account?.revision}:${account?.status}"
    }

    private fun cursor(ref: String?, source: SourceInstance, purpose: String): String? {
        if (ref.isNullOrBlank()) return null
        val (tool, args) = when {
            purpose.startsWith("tracks:") -> "remote_tracks" to JSONObject().put("playlistRef", purpose.removePrefix("tracks:"))
            purpose.startsWith("search-playlists:") -> "search_playlists" to JSONObject().put("sourceId", source.entry.descriptor.id).put("query", purpose.removePrefix("search-playlists:"))
            purpose.startsWith("search:") -> "search_tracks" to JSONObject().put("sourceId", source.entry.descriptor.id).put("query", purpose.removePrefix("search:"))
            else -> "remote_playlists" to JSONObject().put("sourceId", source.entry.descriptor.id).put("section", purpose.removePrefix("directory:"))
        }
        val recovery = aiContinuation(tool, args)
        val value = runCatching { load(ref) }.getOrElse { throw AiToolInputFailure("invalid_remote_cursor", "远端游标不存在或误传了本地游标，请按recovery重新读取", recovery) }
        if (!(value.optString("kind") == "cursor" && value.optString("purpose") == purpose &&
            value.optString("sourceId") == source.entry.descriptor.id && value.optString("stamp") == stamp(source))) throw AiToolInputFailure("invalid_remote_cursor", "远端分页引用不匹配或账号已变化，请重新读取", recovery)
        return value.getString("cursor")
    }

    private fun next(source: SourceInstance, purpose: String, cursor: String?, explicitSearch: Boolean = false): Any = cursor?.let {
        save(JSONObject().put("kind", "cursor").put("sourceId", source.entry.descriptor.id)
            .put("stamp", stamp(source)).put("purpose", purpose).put("cursor", it).put("explicitSearch", explicitSearch))
    } ?: JSONObject.NULL

    private fun save(value: JSONObject): String = UUID.randomUUID().toString().also {
        check(refs.edit().putString(it, value.toString()).commit()) { "无法保存远端引用" }
    }
    private fun load(ref: String): JSONObject = refs.getString(ref, null)?.let(::JSONObject) ?: throw AiToolInputFailure("invalid_reference", "远端引用不存在，请重新读取原目录或搜索结果")

    private fun encode(value: SourceCollection) = JSONObject().put("sourceId", value.identity.sourceId)
        .put("accountId", value.identity.accountId ?: JSONObject.NULL).put("remoteId", value.identity.remoteId)
        .put("stableId", value.stableId).put("title", value.title).put("subtitle", value.subtitle)
        .put("description", value.description).put("count", value.count ?: JSONObject.NULL)
        .put("artwork", JSONObject().put("url", value.artwork.url ?: JSONObject.NULL).put("start", value.artwork.startArgb)
            .put("end", value.artwork.endArgb).put("mark", value.artwork.mark))
        .put("owned", value.owned).put("favorites", value.accountFavorites)
        .put("payload", JSONObject().put("format", value.payload.format).put("version", value.payload.version).put("encoded", value.payload.encoded))

    private fun decode(value: JSONObject): SourceCollection {
        val payload = value.getJSONObject("payload")
        return SourceCollection(TrackIdentity(value.getString("sourceId"), value.optString("accountId").takeIf { it != "null" && it.isNotBlank() },
            value.getString("remoteId")), value.getString("stableId"), value.getString("title"), value.optString("subtitle"), value.optString("description"),
            if (value.isNull("count")) null else value.getInt("count"), artwork = value.optJSONObject("artwork")?.let {
                SourceArtwork(it.optString("url").takeIf { url -> url.isNotBlank() && url != "null" }, it.optLong("start"), it.optLong("end"), it.optString("mark"))
            } ?: SourceArtwork(), owned = value.optBoolean("owned"), accountFavorites = value.optBoolean("favorites"),
            payload = SourcePayload(payload.getString("format"), payload.getInt("version"), payload.getString("encoded")))
    }
}
