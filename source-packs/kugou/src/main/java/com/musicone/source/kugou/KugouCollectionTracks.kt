package com.musicone.source.kugou

import com.musicone.source.sdk.SourceFetchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** list_ver 与 count 均明确时才允许跨进程复用；无版本仅复用本轮目录中的同一个目标对象。 */
internal class KugouCollectionTracks(
    private val directory: File,
    private val store: (File) -> KugouCollectionTracksStore = ::KugouAtomicTracksStore,
    private val mapper: (JSONObject, Boolean) -> MusicTrack = { row, vip -> row.toKugouTrack(vip) },
) {
    private data class Memory(val account: String, val generation: Long, val version: String?, val target: KugouManagedCollection,
        val snapshot: KugouCollectionTrackSnapshot)
    private val mutex = Mutex()
    // 网络核对不占用缓存锁；磁盘读写仍串行，避免读取原子写入中的文件。
    private val cacheMutex = Mutex()
    private val memoryLock = Any()
    // 只常驻最近读取的一张完整歌单；其余完整快照保留在磁盘，不删歌、不截断分页。
    private var memory: Memory? = null

    fun clearMemory() = synchronized(memoryLock) { memory = null }

    suspend fun read(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit,
        load: suspend () -> List<JSONObject>): KugouCollectionTrackSnapshot =
        read(userId, revision, target, check, SourceFetchPolicy.CACHE_FIRST, load)

    suspend fun read(userId: String, revision: Long, target: KugouManagedCollection, check: () -> Unit,
        policy: SourceFetchPolicy, load: suspend () -> List<JSONObject>): KugouCollectionTrackSnapshot =
        readStream(userId, revision, target, check, policy) { accept -> load().forEach(accept) }

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
            // 等待期间可能已有同一版本完成，首屏与后台喜欢状态读取复用完整结果。
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
                // 目录可能落后于完整响应，不能将数量不符的结果记作该版本已验证。
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
                        // 旧格式只读兼容；新写入逐行存储，避免完整 JSON 树及整单字符串同时驻留。
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
