package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** 只编排现有服务；每个分区独立失败、取消和保存，不扩展页面或播放协议。 */
internal class KugouHomeController(
    private val scope: CoroutineScope,
    private val owner: () -> KugouHomeOwner,
    private val discovery: SourceDiscovery,
    private val dailyRecommendations: SourceDailyRecommendations,
    private val snapshots: KugouHomeSnapshotStore,
    private val persist: (KugouHomeState, () -> Unit) -> Unit = snapshots::save,
    private val prepareArtwork: suspend (KugouHomeState) -> Unit = {},
) : AutoCloseable {
    private val mutableState = MutableStateFlow(KugouHomeState())
    val state: StateFlow<KugouHomeState> = mutableState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val versions = mutableMapOf<String, Long>()
    @Volatile private var closed = false
    private val snapshotRevision = java.util.concurrent.atomic.AtomicLong()
    private val persistence = kotlinx.coroutines.sync.Mutex()
    private var activated: KugouHomeOwner? = null
    private var artworkJob: Job? = null
    private var lastDailyAttemptDay: String? = null
    private var lastHomeAttemptDay: String? = null
    private var visible = true
    private val visibleOwners = mutableSetOf<Any>()

    @Synchronized fun updateVisibility(owner: Any, active: Boolean) {
        if (active) visibleOwners.add(owner) else visibleOwners.remove(owner)
        setVisible(visibleOwners.isNotEmpty())
    }
    private var recentRevision = 0L
    private var loadedRecentRevision = 0L

    fun invalidateRecent(account: String) { scope.launch(Dispatchers.IO) {
        synchronized(this@KugouHomeController) {
            if (closed || owner().accountId != account) return@synchronized
            selectOwner()
            recentRevision++
            if (visible && activated != null) start("recent", false)
        }
    } }

    @Synchronized fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            if (jobs["daily"]?.isActive == true) lastDailyAttemptDay = null
            if (jobs["home"]?.isActive == true) lastHomeAttemptDay = null
            if (jobs["recent"]?.isActive == true) recentRevision++
            jobs.values.forEach { it.cancel() }
        } else {
            // 与QQ可见性刷新一致：回到首页核对其他设备的云端历史。
            recentRevision++
            load()
        }
    }

    @Synchronized fun checkOwner(expected: KugouHomeOwner) {
        if (closed || owner() != expected) throw CancellationException("酷狗账号已变化，首页旧请求已取消")
    }

    /** 包启动阶段只恢复本地快照，网络核对仍由原可见性入口触发。 */
    @Synchronized fun restoreSnapshot() { selectOwner() }

    /** 换号立即释放旧首页及请求；新账号仍由原可见入口恢复，不在登录线程读磁盘。 */
    @Synchronized fun clearMemory() {
        snapshotRevision.incrementAndGet()
        artworkJob?.cancel()
        artworkJob = null
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        versions.clear()
        activated = null
        lastDailyAttemptDay = null
        lastHomeAttemptDay = null
        recentRevision = 0
        loadedRecentRevision = 0
        mutableState.value = KugouHomeState()
    }

    /** 每个账号会话首次展示快照并核对一次，返回页面不重复加载。 */
    fun load(): Job = scope.launch(Dispatchers.IO) {
        val expected = synchronized(this@KugouHomeController) { selectOwner() }
        val requests = synchronized(this@KugouHomeController) {
            checkOwner(expected)
            if (!visible) return@synchronized emptyList<Job>()
            val first = activated != expected
            activated = expected
            if (first) prepareSnapshotArtwork(expected)
            val today = snapshots.dayKey()
            val value = mutableState.value
            buildList {
                if ((!value.homeLoaded || value.homeDate != today) && lastHomeAttemptDay != today)
                    add(start("home", false))
                if (expected.accountId != null && (!value.dailyLoaded || value.dailyDate != today) && lastDailyAttemptDay != today)
                    add(start("daily", value.dailyLoaded))
                if (expected.accountId != null && (first || loadedRecentRevision != recentRevision)) add(start("recent", false))
            }
        }
        requests.joinAll()
    }

    fun refreshHome(): Job = scope.launch(Dispatchers.IO) {
        val requests = synchronized(this@KugouHomeController) {
            val expected = selectOwner()
            buildList {
                add(start("home", true))
                if (expected.accountId != null) { add(start("daily", true)); add(start("recent", true)) }
            }
        }
        requests.joinAll()
    }

    fun refreshDaily(): Job = start("daily", true)
    fun refreshRecent(): Job = start("recent", true)
    fun ensureRecent(): Job = start("recent", false)
    fun refreshRecommendations(): Job = start("home", true)
    fun refreshHomeSections(): Job = start("home", true)

    @Synchronized private fun selectOwner(): KugouHomeOwner {
        check(!closed) { "酷狗首页已停用" }
        val expected = owner()
        if (mutableState.value.owner != expected) {
            snapshotRevision.incrementAndGet()
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            versions.clear()
            activated = null
            lastDailyAttemptDay = null
            lastHomeAttemptDay = null
            recentRevision = 0
            loadedRecentRevision = 0
            val cached = snapshots.read(expected.accountId)
            checkOwner(expected)
            mutableState.value = (cached ?: KugouHomeState()).copy(owner = expected)
            artworkJob?.cancel()
            prepareSnapshotArtwork(expected)
        }
        return expected
    }

    private fun prepareSnapshotArtwork(expected: KugouHomeOwner) {
        if (artworkJob?.isActive == true) return
        val snapshot = mutableState.value
        artworkJob = scope.launch(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive(); checkOwner(expected)
                prepareArtwork(snapshot)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 缓存准备失败不改变已恢复内容及原页面状态。 */ }
        }
    }

    @Synchronized private fun start(part: String, refresh: Boolean): Job {
        val expected = selectOwner()
        if (!refresh) jobs[part]?.takeIf { it.isActive }?.let { return it }
        jobs[part]?.cancel()
        val version = (versions[part] ?: 0) + 1
        versions[part] = version
        // 按尝试日期去重，跨日失败后也不因返回页面反复请求；手动刷新仍可重试。
        val requestedRecentRevision = recentRevision
        val homeAttemptDay = if (part == "home") snapshots.dayKey().also { lastHomeAttemptDay = it } else null
        val dailyAttemptDay = if (part == "daily") snapshots.dayKey().also { lastDailyAttemptDay = it } else null
        mutableState.value = mutableState.value.loading(part, true, null)
        return scope.launch(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive(); checkOwner(expected)
                val policy = if (refresh) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST
                val update: (KugouHomeState) -> KugouHomeState = when (part) {
                    "home" -> {
                        val sections = mutableListOf<SourceHomeSection>()
                        val cursors = mutableSetOf<String>()
                        var cursor: String? = null
                        do {
                            currentCoroutineContext().ensureActive(); checkOwner(expected)
                            val page = discovery.home(SourceCursorRequest(cursor, policy = policy))
                            sections += page.items
                            cursor = page.nextCursor
                            check(cursor == null || cursors.add(cursor)) { "酷狗首页分页没有前进" }
                        } while (cursor != null);
                        { value: KugouHomeState -> value.copy(homeSections = sections.toList(), homeLoaded = true, homeDate = homeAttemptDay) }
                    }
                    "daily" -> {
                        val tracks = dailyRecommendations.dailyRecommendations(policy);
                        { value: KugouHomeState -> value.copy(daily = tracks, dailyLoaded = true, dailyDate = dailyAttemptDay) }
                    }
                    else -> {
                        val recent = discovery.recentSnapshot();
                        { value: KugouHomeState -> value.copy(recent = recent, recentLoaded = true) }
                    }
                }
                val context = currentCoroutineContext()
                context.ensureActive()
                val saved = synchronized(this@KugouHomeController) {
                    checkOwner(expected)
                    if (versions[part] != version) return@launch
                    val result = update(mutableState.value).loading(part, false, null)
                    mutableState.value = result
                    val revision = snapshotRevision.incrementAndGet()
                    if (part == "recent") {
                        loadedRecentRevision = requestedRecentRevision
                        // 请求期间的新上报只补一次后续同步，不把旧响应当成最新状态。
                        if (visible && recentRevision != requestedRecentRevision) start("recent", true)
                    }
                    result to revision
                }
                // 大快照编码和落盘不持有页面可见性锁；旧结果不得覆盖较新的成功快照。
                persistence.withLock {
                    val guard = {
                        context.ensureActive()
                        if (closed || owner() != expected || snapshotRevision.get() != saved.second)
                            throw CancellationException("酷狗首页快照已更新")
                    }
                    guard()
                    persist(saved.first, guard)
                }
            } catch (cancelled: CancellationException) {
                synchronized(this@KugouHomeController) {
                    if (!closed && owner() == expected && versions[part] == version) {
                        mutableState.value = mutableState.value.loading(part, false, null)
                    }
                }
                throw cancelled
            }
            catch (failure: Exception) {
                // 隐藏页面引起的线程中断不能成为首页错误提示。
                val context = currentCoroutineContext()
                synchronized(this@KugouHomeController) {
                    context.ensureActive()
                    if (!closed && owner() == expected && versions[part] == version) {
                        mutableState.value = mutableState.value.loading(part, false, failure.message ?: "酷狗首页加载失败")
                    }
                }
            } finally {
                if (!currentCoroutineContext().isActive) synchronized(this@KugouHomeController) {
                    if (!closed && owner() == expected && versions[part] == version) {
                        mutableState.value = mutableState.value.loading(part, false, null)
                    }
                }
            }
        }.also { jobs[part] = it }
    }

    @Synchronized override fun close() {
        closed = true
        clearMemory()
    }
}

private fun KugouHomeState.loading(part: String, loading: Boolean, error: String?) = when (part) {
    "home" -> copy(loadingHome = loading, homeError = error)
    "daily" -> copy(loadingDaily = loading, dailyError = error)
    else -> copy(loadingRecent = loading, recentError = error)
}
