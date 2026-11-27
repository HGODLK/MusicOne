package com.musicone

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class IndexedCacheResource(val key: String, val identity: String, val variant: String, val entered: Long, val trusted: Boolean = false)

/** 只记录音频缓存和所属歌单的引用关系，不重复存音频文件；所有读写都在磁盘缓存的锁内执行。 */
internal class MusicCacheIndex(private val read: () -> String?, private val write: (String) -> Unit) {
    constructor(preferences: SharedPreferences) : this({ preferences.getString("index_v1", "{}") },
        { preferences.edit().putString("index_v1", it).apply() })
    private val resources = linkedMapOf<String, IndexedCacheResource>()
    private val primary = linkedMapOf<String, Map<String, Long>>()
    private val auxiliaryOrder = linkedMapOf<String, Long>()
    private var sequence = 0L

    init {
        val saved = runCatching { JSONObject(read().orEmpty()) }.getOrDefault(JSONObject())
        saved.optJSONArray("resources")?.jsonObjects().orEmpty().forEach { row ->
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

    fun forget(key: String, persist: Boolean = true) {
        // 先留着歌单引用关系，只把入队序号清零，等以后重新缓存时再排进 FIFO。
        resources[key]?.let { resources[key] = it.copy(entered = 0L) }
        auxiliaryOrder.remove(key)
        if (persist) save() else saveAuxiliaryPending = true
    }

    fun auxiliaryOrder(key: String, legacyOrder: Long = 0): Long = auxiliaryOrder.getOrPut(key) {
        (legacyOrder.takeIf { it > 0 } ?: nextOrder()).also { saveAuxiliaryPending = true }
    }
    private var saveAuxiliaryPending = false
    fun flush() { if (saveAuxiliaryPending) { saveAuxiliaryPending = false; save() } }

    fun replacePrimary(group: String, identitiesNewestFirst: List<String>, ordered: Boolean): Boolean {
        val old = primary[group].orEmpty()
        val added = identitiesNewestFirst.distinct().filter { old[it] == null }
        val assigned = if (ordered || old.isNotEmpty()) added.asReversed().associateWith { nextOrder() }.toMutableMap()
            else added.associateWith { 0L }.toMutableMap()
        if (ordered) {
            val unknown = identitiesNewestFirst.distinct().filter { old[it] == 0L }
            val baseline = old.values.filter { it > 0 }.minOrNull() ?: nextOrder()
            // 补录旧缓存的顺序号时排在已有记录前面，别把老歌当成刚缓存的新歌。
            unknown.forEachIndexed { position, identity -> assigned[identity] = baseline - position - 1 }
        }
        val next = identitiesNewestFirst.distinct().associateWith { assigned[it] ?: old.getValue(it) }
        if (old == next) return false
        primary[group] = next
        save()
        return true
    }

    fun changePrimary(group: String, identities: List<String>, added: Boolean): Boolean {
        val old = primary[group].orEmpty()
        val next = old.toMutableMap()
        if (added) identities.asReversed().forEach { if (it !in next) next[it] = nextOrder() }
        else identities.forEach(next::remove)
        if (old == next) return false
        primary[group] = next
        save()
        return true
    }

    fun replaceLibraryPrimary(members: List<Pair<String, Long>>): Boolean {
        val next = members.toMap()
        if (primary["library"] == next) return false
        primary["library"] = next
        save()
        return true
    }

    private fun nextOrder(): Long {
        sequence = maxOf(sequence + 1, System.currentTimeMillis() * 1_000_000)
        return sequence
    }

    private fun save() {
        saveAuxiliaryPending = false
        val rows = JSONArray()
        resources.values.forEach { rows.put(JSONObject().put("key", it.key).put("identity", it.identity)
            .put("variant", it.variant).put("entered", it.entered).put("trusted", it.trusted)) }
        val groups = JSONObject()
        primary.forEach { (group, members) -> groups.put(group, JSONObject(members)) }
        write(JSONObject().put("resources", rows).put("primary", groups)
            .put("auxiliary", JSONObject(auxiliaryOrder)).put("sequence", sequence).toString())
    }
}

/** 去掉会变的账号/票据前缀得到资源变体键：换域名或刷新票据复用同一份缓存，换编码或音轨才分开存。 */
internal fun cacheResourceVariant(key: String): String = if (key.startsWith("bili-"))
    key.replaceFirst(Regex("^bili-(?:mid-[0-9]+|guest)-"), "bili-") else key.substringAfterLast('|')
