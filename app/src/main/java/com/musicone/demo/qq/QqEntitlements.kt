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
    private val pendingSongs = mutableSetOf<Pair<String, String>>()
    private val mutableState = MutableStateFlow(QqEntitlementState())
    val state = mutableState.asStateFlow()
    private data class SessionCache(var member: QqMembership? = null, var loading: Boolean = false,
                                    var retryAtMs: Long = 0L,
                                    val songs: LinkedHashMap<String, QqTrackAccessInfo> = linkedMapOf())
    private val sessions = object : LinkedHashMap<String, SessionCache>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionCache>?): Boolean = size > 4
    }

    fun activate(value: String) = synchronized(lock) {
        if (credential == value) return@synchronized
        credential = value
        sessionRevision += 1L
        refreshJob?.cancel()
        publish(value)
        if (value.isNotBlank()) refreshJob = scope.launch {
            while (true) {
                runInterruptible { membership(value) }
                val expiry = synchronized(lock) {
                    if (credential == value) publish(value)
                    val now = System.currentTimeMillis()
                    val cached = sessions[value]
                    val purchaseExpiry = cached?.songs?.values?.map { it.checkedAtMs + QQ_ENTITLEMENT_TTL_MS }
                        ?.filter { it > now }?.minOrNull()
                    listOfNotNull(cached?.member?.validUntilMs?.takeIf { it > now }, purchaseExpiry).minOrNull()
                }
                delay((expiry?.minus(System.currentTimeMillis()) ?: 30_000L).coerceAtLeast(1_000L))
            }
        }
    }

    fun forget(value: String) = synchronized(lock) {
        sessions.remove(value)
        if (credential == value) activate("")
    }

    fun membership(value: String): QqMembership? {
        if (value.isBlank()) return QqMembership(false, validUntilMs = Long.MAX_VALUE)
        synchronized(lock) {
            val cached = sessions.getOrPut(value) { SessionCache() }
            cached.member?.takeIf { it.validUntilMs > System.currentTimeMillis() }?.let { return it }
            if (cached.loading || cached.retryAtMs > System.currentTimeMillis()) return null
            cached.loading = true
        }
        var result: QqMembership? = null
        try {
            result = QqMembershipClient().query(value)
            return result
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is InterruptedException ||
                Thread.currentThread().isInterrupted || error.stopsPlaybackFallback()) throw error
            return null
        } finally {
            synchronized(lock) {
                sessions[value]?.let {
                    it.loading = false
                    it.member = result
                    it.retryAtMs = if (result == null) System.currentTimeMillis() + 30_000L else 0L
                }
                if (credential == value) publish(value)
            }
        }
    }

    fun rememberMembership(value: String, member: QqMembership?) = synchronized(lock) {
        sessions.getOrPut(value) { SessionCache() }.member = member
        if (credential == value) publish(value)
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
        val member = current.membership?.takeIf { it.validUntilMs > System.currentTimeMillis() }
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
                try {
                    runInterruptible { QqTrackAccessResolver().resolve(listOf(track), value, fresh = false) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException || error is InterruptedException) throw error
                    // 展示角标的后台查询失败时保留限制，实际点播继续走原有登录与安全验证流程。
                    return
                } finally {
                    synchronized(lock) { pendingSongs.remove(key) }
                }
            }
            val expires = cached(value, track.id)?.checkedAtMs?.plus(QQ_ENTITLEMENT_TTL_MS)
            delay((expires?.minus(System.currentTimeMillis()) ?: 30_000L).coerceAtLeast(1_000L))
        }
    }

    private fun publish(value: String) {
        val member = sessions[value]?.member?.takeIf { it.validUntilMs > System.currentTimeMillis() }
        mutableState.value = QqEntitlementState(qqCredentialAccountId(value), member,
            mutableState.value.revision + 1, sessionRevision)
    }
}
