package com.musicone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 可执行权限以宿主白名单为准，音源和模型都不能注册额外写入工具。 */
internal class AiToolExecutor(context: Context, val library: AiLibraryTools, private val client: AiProviderClient,
    private val checkpoint: JSONObject,
    private val showTracks: suspend (List<MusicTrack>, String) -> Unit,
    private val showPlan: suspend (String) -> Unit,
    private val play: suspend (List<MusicTrack>, String) -> JSONObject,
    private val showPlaylist: suspend (MusicPlaylist) -> Unit = {},
    playback: suspend () -> MusicOneUiState? = { null },
) {
    private val store = MusicLibraryStore.get(context)
    val sources = AiSourceTools(context, library, checkpoint)
    private val supplemental = AiSupplementalTools(context, library)
    private val playbackQueue = AiPlaybackQueueTools(library, playback)
    private val lyrics = AiLyricTools(context, library, checkpoint)
    private val dependencies = AiReadDependencies(checkpoint)
    private val effects = AiToolEffects(checkpoint)
    suspend fun execute(call: AiToolCall, configuration: AiConfiguration): JSONObject {
        require(aiToolSchema(configuration).objects().any { it.getJSONObject("function").getString("name") == call.name }) { "此工具未开放" }
        require((configuration.webSearchEnabled && client.webSearchAvailable()) || call.name !in setOf("web_search", "web_fetch")) { "请先配置并验证 DeepSeek，网页搜索暂不可用" }
        require(!checkpoint.optBoolean("responseOnly") || aiResponseOnlyToolAllowed(call.name)) {
            "重新生成回复只允许读取和展示，不能重复播放或准备修改"
        }
        dependencies.check(call)
        val args = JSONObject(call.arguments.ifBlank { "{}" })
        suspend fun operation(): JSONObject = when (call.name) {
            "list_playlists" -> library.directory(args.optional("cursor"))
            "read_playlist" -> library.readPage(args.getString("playlistId"), query = args.optString("query"))
            "search_library" -> library.search(args.getString("query"), args.optJSONArray("playlistIds")?.strings())
            "compare_results" -> library.compareResults(args.getString("operation"), args.getJSONArray("leftHandles").strings(), args.getJSONArray("rightHandles").strings())
            "prepare_remove" -> library.prepareRemove(args.getString("playlistId"), library.resolveSelection(args))
            "playlist_stats" -> library.stats(args.getJSONArray("playlistIds").strings())
            "read_result" -> library.readResult(args.getString("handle"), args.optional("cursor"))
            "read_playback_queue" -> playbackQueue.read()
            "list_sources" -> sources.sources()
            "supplemental_collections" -> supplemental.directory(args.getString("sourceId"), args.optional("cursor"))
            "supplemental_contents" -> supplemental.contents(args.getString("collectionRef"), args.optString("query"), args.optional("cursor"))
            "supplemental_content" -> supplemental.content(args.getString("contentRef"), args.optJSONArray("selectedChildIds")?.strings())
            "remote_playlists" -> sources.remotePlaylists(args.getString("sourceId"), args.getString("section"), args.optional("cursor"))
            "remote_tracks" -> sources.remoteTracks(args.getString("playlistRef"), args.optional("cursor"))
            "search_tracks", "search_playlists" -> {
                val request = checkpoint.optString("latestUserRequest")
                val requestedId = args.optional("sourceId")
                checkpoint.remove("lastSearchAttempt")
                val selectedId = sources.requestedSearchSource(requestedId, args.optional("cursor"))
                if (call.name == "search_tracks") sources.searchTracks(args.getString("query"), selectedId, args.optional("cursor"), request = request)
                else sources.searchPlaylists(args.getString("query"), selectedId, args.optional("cursor"), request = request)
            }
            "show_tracks", "play_tracks" -> {
                val trackRefs = args.getJSONArray("trackRefs").strings()
                val tracks = library.resolveTracks(trackRefs)
                require(tracks.isNotEmpty()) { "未选择歌曲" }
                val validation = sources.validatePlayable(tracks)
                val visible = aiTracksWithAvailability(tracks, validation)
                val title = args.optional("title") ?: "AI 推荐"
                checkpoint.put("displayRecommendationTitle", title)
                val presentation = effects.stage(call, "展示卡片") { if (visible.size >= 5) {
                    val ref = "ai-temporary:" + java.util.UUID.randomUUID()
                    val data = JSONObject().put("title", title).put("reason", args.optString("reason"))
                        .put("trackRefs", JSONArray(trackRefs))
                    val temporary = checkpoint.optJSONObject("temporaryPlaylists") ?: JSONObject().also { checkpoint.put("temporaryPlaylists", it) }
                    temporary.put(ref, data)
                    showPlaylist(aiTemporaryPlaylist(ref, title, args.optString("reason"), visible))
                    JSONObject().put("playlistRef", ref).put("presentation", "temporary_playlist")
                } else {
                    showTracks(visible, args.optString("reason"))
                    JSONObject().put("presentation", "tracks")
                } }
                val result = if (call.name == "play_tracks") playValidated(validation, args.getString("mode"), call)
                    else JSONObject().put("status", "cards_displayed")
                presentation.keys().forEach { result.put(it, presentation.get(it)) }
                if (call.name == "show_tracks") result.put("count", tracks.size)
                result.put("title", title).put("requestedCount", tracks.size).put("trackRefs", JSONArray(trackRefs))
                    .put("skipped", JSONArray(validation.skipped)).put("unknown", JSONArray(validation.unknown))
            }
            "show_playlist", "play_playlist" -> {
                val ref = args.getString("playlistRef")
                val temporary = checkpoint.optJSONObject("temporaryPlaylists")?.optJSONObject(ref)
                val playlist = if (temporary != null) aiTemporaryPlaylist(ref, temporary.getString("title"), temporary.optString("reason"),
                    library.resolveTracks(temporary.getJSONArray("trackRefs").strings()))
                else store.state.value.let { snapshot ->
                    (snapshot.playlists + snapshot.allLocal).firstOrNull { it.id == ref }
                } ?: sources.resolvePlaylist(ref)
                val validation = sources.validatePlayable(playlist.tracks)
                effects.stage(call, "展示卡片") {
                    showPlaylist(playlist.copy(tracks = aiTracksWithAvailability(playlist.tracks, validation)))
                    JSONObject().put("status", "cards_displayed")
                }
                val result = if (call.name == "play_playlist") playValidated(validation, args.optional("mode") ?: "play", call)
                    else JSONObject().put("status", "cards_displayed")
                if (call.name == "show_playlist") result.put("count", playlist.tracks.size)
                result.put("playlistRef", ref).put("title", playlist.title).put("requestedCount", playlist.tracks.size)
                    .put("skipped", JSONArray(validation.skipped)).put("unknown", JSONArray(validation.unknown))
            }
            "prepare_edit_tracks" -> {
                val edits = args.getJSONArray("changes").objects().map { change ->
                    val track = library.resolveTracks(listOf(change.getString("trackRef"))).single()
                    val fields = aiMetadataFields.keys.filter { change.has(it) }.associateWith { change.getString(it) }
                    track to fields
                }
                library.prepareEditTracks(edits)
            }
            "prepare_rename" -> library.prepareRename(args.getJSONArray("changes").objects().associate { it.getString("playlistId") to it.getString("title") })
            "prepare_add" -> {
                val tracks = library.resolveSelection(args)
                sources.validateTracks(tracks)
                library.prepareAdd(args.getString("playlistId"), tracks)
            }
            "prepare_create_playlist" -> {
                val tracks = library.resolveSelection(args)
                sources.validateTracks(tracks)
                library.prepareCreatePlaylist(args.getString("title"), tracks)
            }
            "prepare_delete_playlist" -> library.prepareDeletePlaylist(args.getString("playlistId"))
            "prepare_undo" -> library.prepareUndo(args.getString("operationId"))
            "read_lyrics" -> lyrics.read(args.getString("trackRef"), args.optional("cursor"))
            "prepare_lyric_annotations" -> lyrics.prepareAnnotations(args.getString("trackRef"), args.getString("kind"),
                args.optJSONArray("lines") ?: JSONArray(), args.optBoolean("replace"))
            "prepare_write_lyrics" -> lyrics.prepareWrite(args.getString("trackRef"), args.getString("origin"), args.optional("lyrics"),
                args.optional("evidence"), args.optional("sourceTrackRef"), args.optBoolean("replace"))
            "prepare_remove_lyrics" -> lyrics.prepareRemove(args.getString("trackRef"), args.getString("target"))
            "save_task" -> {
                val notes = args.getString("notes")
                require(notes.toByteArray().size <= 24_000) { "任务记录过长，请保留目标、约束、游标及结论，将曲目留在结果句柄中" }
                checkpoint.put("task", JSONObject(args.toString()))
                JSONObject().put("status", "saved")
            }
            "read_task" -> {
                val text = checkpoint.aiPublicCheckpoint().toString()
                val start = args.optional("cursor")?.toIntOrNull() ?: 0
                require(start in 0..text.length) { "任务记录游标无效" }
                val end = minOf(text.length, start + 3_000)
                JSONObject().put("excerpt", text.substring(start, end)).put("complete", end == text.length)
                    .put("cursor", if (end < text.length) end.toString() else JSONObject.NULL)
            }
            "web_search" -> webResult(client.deepSeekSearch(args.getString("query")))
            "web_fetch" -> client.deepSeekFetch(args.getString("url")).let { page ->
                // 保留完整正文供歌词来源核对，返回给模型的仍按长度截断。
                lyrics.rememberPage(args.getString("url"), page)
                webResult(page)
            }
            else -> error("未知工具")
        }
        val result = effects.saved(call)?.let { JSONObject(it.toString()) }
            ?: if (call.name.startsWith("prepare_")) effects.stage(call, "生成操作明细", transactional = true) { operation() } else operation()
        dependencies.record(call, result)
        if (aiToolHasEffects(call.name) && !aiToolResultFailed(result)) effects.save(call, result)
        if (result.optString("status") == "pending" && result.has("planId")) {
            showPlan(result.getString("planId"))
            result.put("status", "awaiting_confirmation")
        }
        // 读取任务本身不能改写记录，否则下一页的字符游标会漂移。
        if (call.name == "read_task") return result
        // 只记句柄及覆盖量，原始资料仍保存在工具存储，不进入压缩摘要。
        val record = JSONObject().put("tool", call.name)
        listOf("handle", "cursor", "nextCursor", "count", "totalCount", "complete", "planId", "status",
            "scope", "pageComplete", "collectionComplete", "isLastPage", "unknownTotalCount").forEach {
            if (result.has(it)) record.put(it, result.get(it))
        }
        aiSearchReceipt(result).let { search -> search.keys().forEach { record.put(it, search.get(it)) } }
        val previous = checkpoint.optJSONArray("recentResults")?.objects().orEmpty()
        checkpoint.put("recentResults", JSONArray((previous + record).takeLast(16)))
        return result
    }

    private suspend fun playValidated(validation: AiPlaybackValidation, mode: String, call: AiToolCall): JSONObject {
        if (validation.playableTracks.isEmpty()) return JSONObject().put("status", "no_playable_tracks").put("count", 0)
        return effects.stage(call, "提交播放") { play(validation.playableTracks, mode) }
    }

    private fun webResult(data: JSONObject): JSONObject {
        val raw = data.toString()
        // 网页长文明确标注截断，不能让第三方网页挤掉整个任务上下文。
        return if (raw.toByteArray().size <= 18_000) data
        else JSONObject().put("excerpt", raw.take(6_000)).put("complete", false).put("note", "网页内容较长，仅返回开头摘录")
    }
}

internal fun JSONObject.optional(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

internal fun AiLibraryPlan.toUiPlan(): AiPlanUi = AiPlanUi(planId,
    when (kind) { "remove" -> "确认移除歌单成员"; "create_playlist" -> "确认新建本地歌单"; "delete_playlist" -> "确认删除本地歌单"; "rename" -> "确认重命名歌单"; "edit_tracks" -> "确认修改本地歌曲资料"; "lyrics" -> "确认修改歌词"; "undo" -> "确认撤销这批修改"; else -> "确认添加歌曲" },
    details + changes.flatMap { change -> buildList {
        add(change.newTitle?.let { "${change.oldTitle} → $it" }
            ?: "${change.oldTitle}：新增 ${change.tracks.size} 首，跳过 ${change.duplicateCount} 首重复，移除 ${change.removedTracks.size} 首歌曲")
        change.tracks.forEach { add("＋ ${it.title} · ${it.artists}") }
        change.removedTracks.forEach { add("－ ${it.second}") }
    } }, groups = groups, summary = summary)

/** 复用现有队列入口，不创建第二套播放器。 */
internal suspend fun aiPlay(player: MusicPlaybackRuntime?, tracks: List<MusicTrack>, mode: String): JSONObject = withContext(Dispatchers.Main.immediate) {
    requireNotNull(player) { "播放器尚未就绪" }
    require(tracks.isNotEmpty() && tracks.all { it.playable }) { "包含不可播放歌曲，请选择其他可用版本" }
    when (mode) {
        "next" -> player.enqueueTracks(tracks, playNext = true)
        "append" -> player.enqueueTracks(tracks, playNext = false)
        "play" -> {
            val current = player.state.value
            if (current.currentTrack?.id != tracks.first().id || current.currentTrack?.source != tracks.first().source) player.playTrack(tracks.first())

            // 先切到首首，再将其余歌曲依次插在它后面，保留队列其他成员。
            player.enqueueTracks(tracks.drop(1), playNext = true)
            // 明确设置播放意图，解析中的暂时暂停不能被当作切换开关的依据。
            player.setPlaying(true)
        }
        "replace" -> {
            val playlist = MusicPlaylist("ai-queue", MusicSource.LOCAL, "AI 点播", "", "", tracks.size,
                tracks.first().artworkStart, tracks.first().artworkEnd, "♪", tracks)
            // 指定歌曲入口保留既有播放模式，普通整歌单入口会重置循环模式。
            player.playPlaylistTrack(playlist, tracks.first())
            player.setPlaying(true)
        }
        else -> error("未知队列操作")
    }
    JSONObject().put("status", "accepted").put("mode", mode).put("count", tracks.size)
        .put("reply", when (mode) { "next" -> "已加入下一首，共${tracks.size}首"; "append" -> "已加入队列，共${tracks.size}首"; else -> "开始播放${tracks.size}首" })
}

internal fun aiResponseOnlyToolAllowed(name: String): Boolean = name in setOf(
    "list_playlists", "read_playlist", "search_library", "playlist_stats", "compare_results", "read_result", "read_playback_queue", "list_sources",
    "supplemental_collections", "supplemental_contents", "supplemental_content",
    "remote_playlists", "remote_tracks", "search_tracks", "search_playlists", "show_tracks", "show_playlist", "read_task", "web_search", "web_fetch",
    "read_lyrics",
)
