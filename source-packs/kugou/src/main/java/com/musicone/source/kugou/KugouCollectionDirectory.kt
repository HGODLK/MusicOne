package com.musicone.source.kugou

import com.musicone.source.sdk.SourceFetchPolicy
import com.musicone.source.sdk.SourceLibrarySection
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 歌单目录缓存：当前会话里拉过就直接复用，手动刷新时只合并同时在途的并发请求。 */
internal class KugouCollectionDirectory(private val directory: File, private val changed: (String) -> Unit = {}) {
    private data class Flight(val epoch: Long, val owner: Job?, val result: CompletableDeferred<List<KugouManagedCollection>>)
    private val mutex = Mutex()
    private var account = ""
    private var generation = -1L
    private var epoch = 0L
    private var snapshot: List<KugouManagedCollection>? = null
    private var validated = false
    private var validatedAtMs = 0L
    private var backgroundStarted = false
    private var flight: Flight? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun read(userId: String, revision: Long, section: SourceLibrarySection, policy: SourceFetchPolicy,
        check: () -> Unit, load: suspend () -> List<KugouManagedCollection>,
        background: (suspend () -> Unit) -> Unit): List<KugouManagedCollection> {
        var cached: List<KugouManagedCollection>? = null
        var backgroundEpoch: Long? = null
        val selectedEpoch = mutex.withLock {
            currentCoroutineContext().ensureActive(); check()
            select(userId, revision)
            if (policy == SourceFetchPolicy.CACHE_FIRST && snapshot != null) {
                cached = snapshot
                if (!validated && !backgroundStarted) {
                    backgroundStarted = true
                    backgroundEpoch = epoch
                }
            }
            epoch
        }
        backgroundEpoch?.let { expected ->
            background {
                try {
                    val needed = mutex.withLock {
                        account == userId && generation == revision && epoch == expected && !validated
                    }
                    if (needed) {
                        val fresh = fetch(userId, revision, expected, check, load, reuseValidated = true)
                        check()
                        val previous = cached.orEmpty().associate { it.listId to (it.playlist to it.raw.optString("list_ver")) }
                        val current = fresh.associate { it.listId to (it.playlist to it.raw.optString("list_ver")) }
                        if (previous != current) changed(userId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 后台静默刷新失败时继续展示旧缓存，手动刷新才把错抛出去 */ }
            }
        }
        return cached ?: fetch(userId, revision, selectedEpoch, check, load,
            reuseValidated = policy == SourceFetchPolicy.CACHE_FIRST)
    }

    /** 只读本地缓存（内存或磁盘），不发网络请求；没有缓存就返回 null。 */
    suspend fun peek(userId: String, revision: Long, check: () -> Unit): List<KugouManagedCollection>? = mutex.withLock {
        currentCoroutineContext().ensureActive(); check(); select(userId, revision)
        snapshot
    }

    /** [maxAgeMs] 内如果已经联网拉过目录就直接复用，过期了再重新拉，并发调用会合并成一次请求。 */
    suspend fun refreshed(userId: String, revision: Long, maxAgeMs: Long, check: () -> Unit,
        load: suspend () -> List<KugouManagedCollection>): List<KugouManagedCollection> {
        val selected = mutex.withLock {
            currentCoroutineContext().ensureActive(); check(); select(userId, revision)
            if (validated && System.currentTimeMillis() - validatedAtMs < maxAgeMs) snapshot?.let { return it }
            // 缓存已过有效期，重置标记让后面重新发请求（已在途的请求仍会复用）
            validated = false
            epoch
        }
        return fetch(userId, revision, selected, check, load, reuseValidated = true)
    }

    suspend fun current(userId: String, revision: Long, check: () -> Unit,
        load: suspend () -> List<KugouManagedCollection>): List<KugouManagedCollection> {
        val selected = mutex.withLock {
            currentCoroutineContext().ensureActive(); check(); select(userId, revision)
            snapshot?.let { return it }
            epoch
        }
        return fetch(userId, revision, selected, check, load, reuseValidated = true)
    }

    private suspend fun fetch(userId: String, revision: Long, expected: Long, check: () -> Unit,
        load: suspend () -> List<KugouManagedCollection>, reuseValidated: Boolean = false): List<KugouManagedCollection> {
        while (true) {
            var owner = false
            val active = mutex.withLock {
                currentCoroutineContext().ensureActive(); check()
                requireCurrent(userId, revision, expected)
                if (reuseValidated && validated) snapshot?.let { return it }
                // 发起方已经取消的在途请求不再复用，避免新调用者挂到快要销毁的旧任务上
                flight?.takeIf { it.owner?.isActive != false }
                    ?: Flight(expected, currentCoroutineContext()[Job], CompletableDeferred()).also {
                        flight = it
                        owner = true
                    }
            }
            if (!owner) {
                try {
                    val result = active.result.await()
                    currentCoroutineContext().ensureActive(); check()
                    mutex.withLock { requireCurrent(userId, revision, expected) }
                    return result
                } catch (cancelled: CancellationException) {
                    // 如果只是发起请求的那个协程被取消了，其他还在等的调用方自己重新拉一次
                    currentCoroutineContext().ensureActive(); check()
                    mutex.withLock { requireCurrent(userId, revision, expected) }
                    continue
                }
            }
            try {
                val result = load()
                currentCoroutineContext().ensureActive(); check()
                mutex.withLock {
                    requireCurrent(userId, revision, active.epoch)
                    if (flight !== active) throw CancellationException("酷狗目录请求已被替换")
                    check()
                    store(userId, result)
                    snapshot = result
                    validated = true
                    validatedAtMs = System.currentTimeMillis()
                    flight = null
                    active.result.complete(result)
                }
                return result
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (flight === active) flight = null
                        active.result.completeExceptionally(failure)
                    }
                }
                throw failure
            }
        }
    }

    private fun requireCurrent(userId: String, revision: Long, expected: Long) {
        if (account != userId || generation != revision || epoch != expected) {
            throw CancellationException("酷狗目录账号或快照已失效")
        }
    }

    suspend fun invalidate(userId: String, revision: Long) = mutex.withLock {
        if (account == userId && generation == revision) {
            reset()
            snapshot = null
        }
        if (account != userId || generation == revision) {
            val target = file(userId)
            target.delete()
            File("${target.path}.bak").delete()
            File("${target.path}.pending").delete()
        }
    }

    private fun reset() {
        epoch++
        flight?.result?.cancel(CancellationException("酷狗目录已失效"))
        flight = null
        validated = false
        backgroundStarted = false
    }

    private fun select(userId: String, revision: Long) {
        require(userId.toLongOrNull()?.let { it > 0 } == true)
        if (account == userId && generation == revision) return
        reset()
        account = userId
        generation = revision
        snapshot = runCatching {
            val target = file(userId)
            val root = JSONObject((target.takeIf(File::exists) ?: File("${target.path}.bak")).readText())
            require(root.getInt("schema") == 1 && root.getString("account") == userId)
            val rows = root.getJSONArray("rows")
            (0 until rows.length()).map { index ->
                val value = rows.getJSONObject(index)
                kugouManagedCollection(value.getJSONObject("raw"), value.getLong("version"), userId)
                    ?: error("歌单快照编号无效")
            }
        }.getOrNull()
    }

    private fun file(userId: String) = File(directory, "account-$userId.json")
    private fun store(userId: String, result: List<KugouManagedCollection>) {
        directory.mkdirs()
        val rows = JSONArray(result.map { JSONObject().put("raw", it.raw).put("version", it.totalVersion) })
        val text = JSONObject().put("schema", 1).put("account", userId).put("rows", rows).toString()
        val target = file(userId)
        val pending = File(directory, "${target.name}.pending")
        val backup = File(directory, "${target.name}.bak")
        try {
            pending.writeText(text)
            if (target.exists()) {
                if (backup.exists()) check(backup.delete()) { "酷狗目录旧备份清理失败" }
                check(target.renameTo(backup)) { "酷狗目录旧快照备份失败" }
            }
            if (!pending.renameTo(target)) {
                backup.renameTo(target)
                error("酷狗目录快照保存失败")
            }
            backup.delete()
        } finally { pending.delete() }
    }
}
