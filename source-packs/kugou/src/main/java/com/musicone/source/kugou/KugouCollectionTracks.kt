package com.musicone.source.kugou

import com.musicone.source.sdk.SourceFetchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** 只有 list_ver 和曲目数都明确时才跨启动复用磁盘缓存，否则只在当前会话内复用。 */
internal class KugouCollectionTracks(
    private val directory: File,
    private val store: (File) -> KugouCollectionTracksStore = ::KugouAtomicTracksStore,
    private val mapper: (JSONObject, Boolean) -> MusicTrack = { row, vip -> row.toKugouTrack(vip) },
) {
    private data class Memory(val account: String, val generation: Long, val version: String?, val target: KugouManagedCollection,
        val snapshot: KugouCollectionTrackSnapshot)
    private val mutex = Mutex()
    // 发网络请求时不占缓存锁，只对磁盘读写加锁，防止读到写一半的文件
    private val cacheMutex = Mutex()
    private val memoryLock = Any()
    // 内存里只留最近打开的一张完整歌单，其他的放磁盘，控制内存占用
    private var memory: Memory? = null

    fun clearMemory() = synchronized(memoryLock) { memory = null }

    suspend fun read(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit,
        load: suspend () -> List<JSONObject>): KugouCollectionTrackSnapshot =
        read(userId, revision, target, check, SourceFetchPolicy.CACHE_FIRST, load)

    suspend fun read(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit,
        policy: SourceFetchPolicy, load: suspend () -> List<JSONObject>): KugouCollectionTrackSnapshot =
        readStream(userId, revision, target, check, policy) { accept -> load().forEach(accept) }

    /** 只读本地歌单缓存，版本号和目录一致才返回，不发网络请求；没命中返回 null。 */
    suspend fun peek(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit): KugouCollectionTrackSnapshot? {
        validateIdentity(userId, target.listId)
        val context = currentCoroutineContext()
        val guard = { context.ensureActive(); check() }
        return cached(userId, revision, target, target.trackSnapshotVersion(), guard)
    }

    suspend fun readStream(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit,
        policy: SourceFetchPolicy, load: suspend ((JSONObject) -> Unit) -> Unit): KugouCollectionTrackSnapshot {
        validateIdentity(userId, target.listId)
        val context = currentCoroutineContext()
        val guard = { context.ensureActive(); check() }
        guard()
        val version = target.trackSnapshotVersion()
        if (policy == SourceFetchPolicy.CACHE_FIRST) {
            cached(userId, revision, target, version, guard)?.let { return it }
        }
        return mutex.withLock {
            // 等锁期间可能已经有别人把同一版本拉完了，这里先查一次缓存直接复用
            if (policy == SourceFetchPolicy.CACHE_FIRST) {
                cached(userId, revision, target, version, guard)?.let { return@withLock it }
            }
            guard()
            val builder = KugouCollectionSnapshotBuilder(userId, target.listId, version, mapper, guard)
            load(builder::add)
            guard()
            val result = builder.finish()
            val matchesCount = target.matchesTrackCount(result.count)
            cacheMutex.withLock {
                guard()
                if (version != null && matchesCount) {
                    val header = JSONObject().put("schema", 2).put("account", userId).put("listId", target.listId)
                        .put("version", version).put("count", result.count).toString()
                    store(file(userId, target.listId)).writeRows(header, result.rows, guard)
                }
                // 目录缓存的曲目数可能比实际拉回来的旧，数量对不上时别把这个版本标成已确认
                synchronized(memoryLock) {
                    guard()
                    memory = if (matchesCount) Memory(userId, revision, version, target, result) else null
                }
            }
            result
        }
    }

    private suspend fun cached(userId: String, revision: Long, target: KugouManagedCollection,
        version: String?, guard: () -> Unit): KugouCollectionTrackSnapshot? = cacheMutex.withLock {
        synchronized(memoryLock) {
            guard()
            memory?.takeIf { existing ->
                existing.account == userId && existing.target.listId == target.listId &&
                    existing.generation == revision &&
                    (if (version != null) existing.version == version else existing.version == null && existing.target === target)
            }?.let { return@withLock it.snapshot }
        }
        val storage = store(file(userId, target.listId))
        if (version != null) {
            val cached = try {
                val builder = KugouCollectionSnapshotBuilder(userId, target.listId, version, mapper, guard)
                var header = false
                var expectedCount: Int? = null
                val found = storage.readLines { line ->
                    guard()
                    val row = JSONObject(line)
                    if (!header) {
                        val schema = row.getInt("schema")
                        require(schema == 1 || schema == 2)
                        require(row.getString("account") == userId && row.getString("listId") == target.listId &&
                            row.getString("version") == version)
                        header = true
                        if (schema == 2) expectedCount = row.getInt("count").also { require(it >= 0) }
                        // 兼容读取 v1 旧格式缓存；新缓存统一按行存储，省内存
                        if (schema == 1) {
                            val rows = row.getJSONArray("rows")
                            for (index in 0 until rows.length()) builder.add(rows.getJSONObject(index))
                        }
                    } else builder.add(row)
                }
                if (found && header) builder.finish().also {
                    require(expectedCount == null || expectedCount == it.count)
                    require(target.matchesTrackCount(it.count))
                } else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (cached != null) {
                guard()
                synchronized(memoryLock) { guard(); memory = Memory(userId, revision, version, target, cached) }
                return@withLock cached
            }
        }
        null
    }

    suspend fun invalidate(userId: String, listId: String? = null) = mutex.withLock {
        cacheMutex.withLock {
            require(userId.toLongOrNull()?.let { it > 0 } == true)
            if (listId != null) validateIdentity(userId, listId)
            val knownFiles = synchronized(memoryLock) {
                val old = memory?.takeIf { it.account == userId && (listId == null || it.target.listId == listId) }
                if (old != null) memory = null
                listOfNotNull(old?.let { file(userId, it.target.listId).name })
            }
            if (listId != null) store(file(userId, listId)).delete()
            else (knownFiles + directory.listFiles().orEmpty().filter { it.name.startsWith("account-$userId-list-") }
                .map { it.name.removeSuffix(".bak").removeSuffix(".new") }).distinct()
                .forEach { store(File(directory, it)).delete() }
        }
    }

    private fun file(userId: String, listId: String) = File(directory, "account-$userId-list-$listId.json")

}
private fun KugouManagedCollection.matchesTrackCount(actual: Int): Boolean {
    val count = raw.opt("count")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
    return count == null || count < 0 || count == actual.toLong()
}

internal fun KugouManagedCollection.trackSnapshotVersion(): String? {
    val version = raw.opt("list_ver")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
    val count = raw.opt("count")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
    return if (version != null && version > 0 && count != null && count >= 0) "$version:$count" else null
}

private fun validateIdentity(userId: String, listId: String) {
    require(userId.toLongOrNull()?.let { it > 0 } == true && listId.toLongOrNull()?.let { it > 0 } == true) {
        "酷狗账号或云歌单编号无效"
    }
}
