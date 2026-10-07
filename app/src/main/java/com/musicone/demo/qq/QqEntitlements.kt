package com.musicone.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal data class QqEntitlementState(
    val accountId: String = "",
    val membership: QqMembership? = null,
    val revision: Long = 0L,
    val sessionRevision: Long = 0L,
)

/** 页面只订阅权益结果；会话刷新和旧账号响应隔离都在 QQ 数据层完成。 */
internal object QqEntitlements {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var credential = ""
    private var sessionRevision = 0L
    private var refreshJob: Job? = null
    private var refreshWakeup = Channel<Unit>(Channel.CONFLATED)
    private val pendingSongs = mutableSetOf<Pair<String, String>>()
    private val mutableState = MutableStateFlow(QqEntitlementState())
    val state = mutableState.asStateFlow()
    private data class SessionCache(val membership: QqMembershipCache = QqMembershipCache(),
                                    val songs: LinkedHashMap<String, QqTrackAccessInfo> = linkedMapOf())
    private val sessions = object : LinkedHashMap<String, SessionCache>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionCache>?): Boolean = size > 4
    }

    fun activate(value: String) = synchronized(lock) {
        if (credential == value && (value.isBlank() || refreshJob?.isActive == true)) return@synchronized
        if (credential != value) {
            credential = value
            sessionRevision += 1L
        }
        refreshJob?.cancel()
        refreshWakeup.close()
        val wakeup = Channel<Unit>(Channel.CONFLATED).also { refreshWakeup = it }
        publish(value)
        if (value.isNotBlank()) refreshJob = scope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    runInterruptible { membership(value) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 鉴权或网络失败已经记录重试时间，不能终止整个权益刷新任务。
                    currentCoroutineContext().ensureActive()
                }
                val expiry = synchronized(lock) {
                    if (credential != value) return@launch
                    publish(value)
                    val now = System.currentTimeMillis()
                    val cached = sessions[value]
                    val purchaseExpiry = cached?.songs?.values?.map { it.checkedAtMs + QQ_ENTITLEMENT_TTL_MS }
                        ?.filter { it > now }?.minOrNull()
                    listOfNotNull(cached?.membership?.nextRefreshMs(now), purchaseExpiry).minOrNull()
                }
                val waitMs = (expiry?.minus(System.currentTimeMillis()) ?: 30_000L).coerceAtLeast(1_000L)
                withTimeoutOrNull(waitMs) { wakeup.receive() }
            }
        }
    }

    fun refreshOnForeground() = synchronized(lock) {
        if (credential.isBlank()) return@synchronized
        sessions[credential]?.membership?.resume()
        activate(credential)
        refreshWakeup.trySend(Unit)
    }

    fun forget(value: String) = synchronized(lock) {
        sessions.remove(value)
        if (credential == value) activate("")
    }

    fun membership(value: String): QqMembership? {
        if (value.isBlank()) return QqMembership(false, validUntilMs = Long.MAX_VALUE)
        val cache = synchronized(lock) {
            val cached = sessions.getOrPut(value) { SessionCache() }
            val now = System.currentTimeMillis()
            if (!cached.membership.begin(now)) return cached.membership.current(now)
            cached
        }
        var result: QqMembership? = null
        try {
            result = QqMembershipClient().query(value)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is InterruptedException ||
                Thread.currentThread().isInterrupted || error.stopsPlaybackFallback()) throw error
        } finally {
            synchronized(lock) {
                if (sessions[value] === cache) {
                    cache.membership.complete(result, System.currentTimeMillis())
                }
                if (credential == value) publish(value)
            }
        }
        return synchronized(lock) { cache.membership.current(System.currentTimeMillis()) }
    }

    fun rememberMembership(value: String, member: QqMembership?) = synchronized(lock) {
        val cache = sessions.getOrPut(value) { SessionCache() }.membership
        cache.remember(member, System.currentTimeMillis())
        if (credential == value) publish(value)
        cache.current(System.currentTimeMillis())
    }

    fun remember(value: String, trackId: String, info: QqTrackAccessInfo) = synchronized(lock) {
        val songs = sessions.getOrPut(value) { SessionCache() }.songs
        val changed = songs.put(trackId, info) != info
        while (songs.size > 512) songs.remove(songs.keys.first())
        if (changed && credential == value) publish(value)
    }

    fun cached(value: String, trackId: String): QqTrackAccessInfo? = synchronized(lock) {
        sessions[value]?.songs?.get(trackId)?.takeIf {
            System.currentTimeMillis() - it.checkedAtMs in 0 until QQ_ENTITLEMENT_TTL_MS
        }
    }

    fun displayTrack(track: MusicTrack, current: QqEntitlementState): MusicTrack {
        val info = synchronized(lock) { cached(credential, track.id) } ?: track.qqAccess
        val member = current.membership?.effectiveAt(System.currentTimeMillis())
        return track.copy(qqAccess = info).withQqAccess(current.accountId, member?.vip == true, member?.superVip == true)
    }

    /** 只刷新仍在展示的限制歌曲，已购缓存到期后重新查询，不会永久变回付费角标。 */
    suspend fun observeTrack(track: MusicTrack, generation: Long) {
        if (track.qqAccess?.complete == true && track.qqAccess.kind == null) return
        val value = synchronized(lock) { credential }
        if (value.isBlank()) return
        val key = value to track.id
        while (true) {
            currentCoroutineContext().ensureActive()
            val acquired = synchronized(lock) {
                if (credential != value || sessionRevision != generation) return
                pendingSongs.add(key)
            }
            if (acquired) {
                var failed = false
                try {
                    runInterruptible { QqTrackAccessResolver().resolve(listOf(track), value, fresh = false) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException || error is InterruptedException) throw error
                    // 后台失败不弹出验证，也不能让仍显示的歌曲永久失去重试。
                    failed = true
                } finally {
                    synchronized(lock) { pendingSongs.remove(key) }
                }
                if (failed) { delay(30_000L); continue }
            }
            val expires = cached(value, track.id)?.checkedAtMs?.plus(QQ_ENTITLEMENT_TTL_MS)
            delay((expires?.minus(System.currentTimeMillis()) ?: 30_000L).coerceAtLeast(1_000L))
        }
    }

    private fun publish(value: String) {
        val member = sessions[value]?.membership?.current(System.currentTimeMillis())
        mutableState.value = QqEntitlementState(qqCredentialAccountId(value), member,
            mutableState.value.revision + 1, sessionRevision)
    }
}
