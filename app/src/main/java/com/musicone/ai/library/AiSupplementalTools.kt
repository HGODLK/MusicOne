package com.musicone

import android.content.Context
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toMusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 只读补充源公开目录及内容；转换结果仍须经过既有本地修改确认。 */
internal class AiSupplementalTools(context: Context, private val library: AiLibraryTools) {
    private val controller = SourcePackageController.get(context.applicationContext)
    private val refs = context.applicationContext.getSharedPreferences("musicone_ai_supplemental_refs", Context.MODE_PRIVATE)

    suspend fun directory(sourceId: String, cursor: String?): JSONObject = withContext(Dispatchers.IO) {
        val source = source(sourceId)
        val stamp = stamp(source)
        val service = requireNotNull(source.entry.remoteCollections) { "此补充源不提供收藏目录" }
        val page = source.withOperation { service.collections(SourceCursorRequest(rawCursor(cursor, source, "directory"), 50)) }
        verify(source, stamp)
        JSONObject().put("items", JSONArray(page.items.map { collection ->
            validate(source, collection.identity)
            val ref = save(source, "collection", JSONObject().put("remoteId", collection.identity.remoteId)
                .put("accountId", collection.identity.accountId ?: JSONObject.NULL).put("title", collection.title)
                .put("count", collection.count ?: JSONObject.NULL)
                .put("artwork", JSONObject().put("url", collection.artwork.url ?: JSONObject.NULL).put("start", collection.artwork.startArgb)
                    .put("end", collection.artwork.endArgb).put("mark", collection.artwork.mark))
                .put("payload", payload(collection.payload)))
            JSONObject().put("collectionRef", ref).put("title", collection.title).put("count", collection.count ?: JSONObject.NULL)
                .put("sourceId", sourceId).put("type", "remote_supplemental").put("canEditMembers", false).put("canDelete", false)
        })).put("nextCursor", next(source, "directory", page.nextCursor)).put("isLastPage", page.nextCursor == null)
    }

    suspend fun contents(collectionRef: String, query: String, cursor: String?): JSONObject = withContext(Dispatchers.IO) {
        val (source, ref) = reference(collectionRef, "collection")
        val stamp = stamp(source)
        val collection = collection(source, ref)
        val purpose = "contents:$collectionRef:$query"
        val page = source.withOperation { requireNotNull(source.entry.remoteCollections).contents(collection, query.takeIf { it.isNotBlank() },
            SourceCursorRequest(rawCursor(cursor, source, purpose), 50)) }
        verify(source, stamp)
        JSONObject().put("items", JSONArray(page.items.map { content ->
            validate(source, content.identity)
            val handle = save(source, "content", JSONObject().put("collectionRef", collectionRef).put("query", query)
                .put("remoteId", content.identity.remoteId).put("accountId", content.identity.accountId ?: JSONObject.NULL))
            JSONObject().put("contentRef", handle).put("title", content.title).put("author", content.author?.name ?: "unknown")
                .put("sourceId", source.entry.descriptor.id).put("type", "remote_supplemental_content")
        })).put("nextCursor", next(source, purpose, page.nextCursor)).put("isLastPage", page.nextCursor == null)
    }

    /** 先完整读取子项目供选择；省略选择参数仅预览，不静默导入默认分P。 */
    suspend fun content(contentRef: String, selectedIds: List<String>?): JSONObject = withContext(Dispatchers.IO) {
        val (source, ref) = reference(contentRef, "content")
        val stamp = stamp(source)
        val content = loadContent(source, ref)
        val detailed = source.withOperation { source.entry.contents?.detail(content) ?: content }
        validate(source, detailed.identity)
        val children = source.withOperation {
            source.entry.childItems?.let { service -> pages { service.children(detailed, SourceCursorRequest(it, 100)) } }.orEmpty()
        }
        verify(source, stamp)
        children.forEach { validate(source, it.identity) }
        val result = JSONObject().put("contentRef", contentRef).put("title", detailed.title).put("childrenComplete", true)
            .put("children", JSONArray(children.map { JSONObject().put("childId", it.identity.remoteId).put("index", it.index)
                .put("title", it.title).put("initiallySelected", it.initiallySelected) }))
        if (children.isNotEmpty() && selectedIds == null) return@withContext result.put("selectionRequired", true)
        val selected = aiSelectedSupplementalChildren(children, selectedIds)
        val tracks = source.withOperation { supplementalTracks(source.entry, detailed, selected) }
        verify(source, stamp)
        tracks.forEach { validate(source, it.track.identity) }
        val registered = library.registerResult(tracks.map { it.toMusicTrack() }, detailed.title, true)
        library.readResult(registered.getString("handle")).put("contentRef", contentRef).put("selectionRequired", false)
    }

    private suspend fun loadContent(source: SourceInstance, ref: JSONObject): SourceContent {
        val (_, folder) = reference(ref.getString("collectionRef"), "collection")
        val service = requireNotNull(source.entry.remoteCollections)
        val targetAccount = ref.optional("accountId")
        return source.withOperation {
            var cursor: String? = null
            val visited = hashSetOf<String>()
            var found: SourceContent? = null
            do {
                val page = service.contents(collection(source, folder), ref.optional("query"), SourceCursorRequest(cursor, 100))
                found = page.items.firstOrNull { it.identity.remoteId == ref.getString("remoteId") && it.identity.accountId == targetAccount }
                cursor = page.nextCursor
                check(cursor == null || visited.add(cursor)) { "音源返回重复分页" }
            } while (found == null && cursor != null)
            requireNotNull(found) { "内容已变化，请重新读取收藏夹" }
        }
    }
    private suspend fun source(id: String): SourceInstance {
        controller.awaitStartup()
        return requireNotNull(controller.running(id)) { "音源未启用" }.also {
            require(it.entry.descriptor.kind == SourceKind.SUPPLEMENTAL) { "仅支持补充音源" }
        }
    }
    private suspend fun reference(id: String, kind: String): Pair<SourceInstance, JSONObject> {
        val ref = load(id)
        require(ref.getString("kind") == kind) { "引用类型错误" }
        val source = source(ref.getString("sourceId"))
        verify(source, ref.getString("stamp"))
        return source to ref
    }
    private fun stamp(source: SourceInstance): String = source.entry.authentication?.currentAccount().let {
        "${source.entry.descriptor.versionCode}:${it?.account?.id}:${it?.revision}:${it?.status}"
    }
    private fun verify(source: SourceInstance, stamp: String) {
        check(controller.running(source.entry.descriptor.id) === source && stamp(source) == stamp) { "音源或账号已变化，请重新读取" }
    }
    private fun validate(source: SourceInstance, id: TrackIdentity) {
        require(id.sourceId == source.entry.descriptor.id && (id.accountId == null || id.accountId == source.entry.authentication?.currentAccount()?.account?.id)) { "音源返回了不一致的身份" }
    }
    private fun collection(source: SourceInstance, ref: JSONObject) = SourceRemoteCollection(
        TrackIdentity(source.entry.descriptor.id, ref.optional("accountId"), ref.getString("remoteId")), ref.getString("title"),
        count = if (ref.isNull("count")) null else ref.getInt("count"),
        artwork = ref.getJSONObject("artwork").let { SourceArtwork(it.optional("url"), it.optLong("start"), it.optLong("end"), it.optString("mark")) },
        payload = ref.getJSONObject("payload").let { SourcePayload(it.getString("format"), it.getInt("version"), it.getString("encoded")) })
    private fun payload(value: SourcePayload) = JSONObject().put("format", value.format).put("version", value.version).put("encoded", value.encoded)
    private fun save(source: SourceInstance, kind: String, value: JSONObject): String = UUID.randomUUID().toString().also {
        value.put("kind", kind).put("sourceId", source.entry.descriptor.id).put("stamp", stamp(source))
        check(refs.edit().putString(it, value.toString()).commit()) { "无法保存补充源引用" }
    }
    private fun load(id: String) = refs.getString(id, null)?.let(::JSONObject) ?: error("补充源引用不存在")
    private fun rawCursor(id: String?, source: SourceInstance, purpose: String): String? = id?.let {
        val ref = load(it)
        require(ref.optString("kind") == "cursor" && ref.optString("sourceId") == source.entry.descriptor.id && ref.optString("purpose") == purpose) { "分页不属于当前请求" }
        verify(source, ref.getString("stamp")); ref.getString("cursor")
    }
    private fun next(source: SourceInstance, purpose: String, cursor: String?): Any = cursor?.let {
        save(source, "cursor", JSONObject().put("purpose", purpose).put("cursor", it))
    } ?: JSONObject.NULL
    private suspend fun <T> pages(read: suspend (String?) -> SourcePage<T>): List<T> {
        val items = mutableListOf<T>(); val seen = hashSetOf<String>(); var cursor: String? = null
        do { val page = read(cursor); items += page.items; cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "音源返回重复分页" }
        } while (cursor != null)
        return items
    }
}

/** 子项目校验只适用于真实声明的子项目；普通单曲无需伪造分P选择。 */
internal fun aiSelectedSupplementalChildren(children: List<SourceChildItem>, ids: List<String>?): List<SourceChildItem> {
    if (children.isEmpty()) { require(ids.isNullOrEmpty()) { "此内容没有子项目" }; return emptyList() }
    require(!ids.isNullOrEmpty()) { "请至少选择一个子项目" }
    require(ids.all { id -> children.any { it.identity.remoteId == id } }) { "子项目不属于当前内容" }
    return children.filter { it.identity.remoteId in ids }
}
