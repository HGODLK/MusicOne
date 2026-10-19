package com.musicone.demo

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class IndexedCacheResource(val key: String, val identity: String, val variant: String, val entered: Long, val trusted: Boolean = false)

/** 只保存共享资源与歌单引用，不创建第二份音频文件。由磁盘缓存的同一把锁串行访问。 */
internal class MusicCacheIndex(private val read: () -> String?, private val write: (String) -> Unit) {
    constructor(preferences: SharedPreferences) : this({ preferences.getString("index_v1", "{}") },
        { preferences.edit().putString("index_v1", it).apply() })
    private val resources = linkedMapOf<String, IndexedCacheResource>()
    private val primary = linkedMapOf<String, Map<String, Long>>()
    private val auxiliaryOrder = linkedMapOf<String, Long>()
    private var sequence = 0L

    init {
        val saved = runCatching { JSONObject(read().orEmpty()) }.getOrDefault(JSONObject())
        saved.optJSONArray("resources")?.searchObjects().orEmpty().forEach { row ->
            val key = row.optString("key")
            resources[key] = IndexedCacheResource(key, row.optString("identity"), row.optString("variant"), row.optLong("entered"), row.optBoolean("trusted"))
        }
        saved.optJSONObject("primary")?.let { groups -> groups.keys().forEach { group ->
            val members = groups.getJSONObject(group)
            primary[group] = members.keys().asSequence().associateWith { members.getLong(it) }
        } }
        saved.optJSONObject("auxiliary")?.let { rows -> rows.keys().forEach { auxiliaryOrder[it] = rows.getLong(it) } }
        sequence = saved.optLong("sequence")
    }

    fun resource(key: String) = resources[key]
    fun resources(identity: String) = resources.values.filter { it.identity == identity }
    fun primaryOrder(identity: String): Long? = primary.values.mapNotNull { it[identity] }.maxOrNull()
    fun isPrimary(identity: String) = primaryOrder(identity) != null

    fun importResource(key: String, track: MusicTrack, entered: Long = 0L) {
        importIdentity(key, track.cacheIdentity(), entered)
    }

    fun importIdentity(key: String, identity: String, entered: Long = 0L) {
        if (key !in resources) {
            resources[key] = IndexedCacheResource(key, identity, cacheResourceVariant(key), entered)
            save()
        }
    }

    fun register(key: String, track: MusicTrack, hasBytes: (String) -> Boolean): String {
        val identity = track.cacheIdentity()
        val variant = cacheResourceVariant(key)
        val existing = resources.values.firstOrNull { it.identity == identity && it.variant == variant && it.trusted && hasBytes(it.key) }
        if (existing != null) return existing.key
        importResource(key, track)
        return key
    }

    fun trust(key: String, trusted: Boolean) {
        val entry = resources[key] ?: return
        if (entry.trusted != trusted) { resources[key] = entry.copy(trusted = trusted); save() }
    }

    fun markWritten(key: String) {
        val entry = resources[key] ?: return
        if (entry.entered == 0L) {
            resources[key] = entry.copy(entered = nextOrder())
            save()
        }
    }

    fun forget(key: String) {
        // 保留资源身份和歌单关系，真正重新进入缓存时才重新记录 FIFO 次序。
        resources[key]?.let { resources[key] = it.copy(entered = 0L) }
        auxiliaryOrder.remove(key)
        save()
    }

    fun auxiliaryOrder(key: String, legacyOrder: Long = 0): Long = auxiliaryOrder.getOrPut(key) {
        (legacyOrder.takeIf { it > 0 } ?: nextOrder()).also { saveAuxiliaryPending = true }
    }
    private var saveAuxiliaryPending = false
    fun flush() { if (saveAuxiliaryPending) { saveAuxiliaryPending = false; save() } }

    fun replacePrimary(group: String, identitiesNewestFirst: List<String>, ordered: Boolean) {
        val old = primary[group].orEmpty()
        val added = identitiesNewestFirst.distinct().filter { old[it] == null }
        val assigned = if (ordered || old.isNotEmpty()) added.asReversed().associateWith { nextOrder() }.toMutableMap()
            else added.associateWith { 0L }.toMutableMap()
        if (ordered) {
            val unknown = identitiesNewestFirst.distinct().filter { old[it] == 0L }
            val baseline = old.values.filter { it > 0 }.minOrNull() ?: nextOrder()
            // 首次补齐的历史顺序排在本轮已知新增之前，不能把旧歌曲重新提升为最新。
            unknown.forEachIndexed { position, identity -> assigned[identity] = baseline - position - 1 }
        }
        val next = identitiesNewestFirst.distinct().associateWith { assigned[it] ?: old.getValue(it) }
        if (old != next) { primary[group] = next; save() }
    }

    fun changePrimary(group: String, identities: List<String>, added: Boolean) {
        val old = primary[group].orEmpty()
        val next = old.toMutableMap()
        if (added) identities.asReversed().forEach { if (it !in next) next[it] = nextOrder() }
        else identities.forEach(next::remove)
        if (old != next) { primary[group] = next; save() }
    }

    fun replaceLibraryPrimary(members: List<Pair<String, Long>>) {
        val next = members.toMap()
        if (primary["library"] != next) { primary["library"] = next; save() }
    }

    private fun nextOrder(): Long {
        sequence = maxOf(sequence + 1, System.currentTimeMillis() * 1_000_000)
        return sequence
    }

    private fun save() {
        val rows = JSONArray()
        resources.values.forEach { rows.put(JSONObject().put("key", it.key).put("identity", it.identity)
            .put("variant", it.variant).put("entered", it.entered).put("trusted", it.trusted)) }
        val groups = JSONObject()
        primary.forEach { (group, members) -> groups.put(group, JSONObject(members)) }
        write(JSONObject().put("resources", rows).put("primary", groups)
            .put("auxiliary", JSONObject(auxiliaryOrder)).put("sequence", sequence).toString())
    }
}

/** 票据、账号和 CDN 域名变化不生成新的持久音频副本，编码或音轨变化仍必须隔离字节。 */
internal fun cacheResourceVariant(key: String): String = if (key.startsWith("bili-"))
    key.replaceFirst(Regex("^bili-(?:mid-[0-9]+|guest)-"), "bili-") else key.substringAfterLast('|')
