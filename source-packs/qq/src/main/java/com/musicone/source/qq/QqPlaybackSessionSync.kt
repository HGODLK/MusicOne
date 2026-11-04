package com.musicone.source.qq

import android.content.Context
import com.musicone.source.qq.internal.*
import com.musicone.source.sdk.SourcePlaybackSessionEvent
import com.musicone.source.sdk.SourcePlaybackSessionEventType
import com.musicone.source.sdk.SourcePlaybackSessionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** 只消费宿主真实收听累计值，统计业务不进入原取源和最近播放链路。 */
internal class QqPlaybackSessionSync(context: Context, private val auth: QqAuthentication, private val api: QqApiClient) :
    SourcePlaybackSessionObserver {
    private data class Ticket(val key: String, val revision: Long, val track: MusicTrack, val vkey: String)
    private data class Session(
        val id: String, val stableId: String, val account: String, val revision: Long, var track: MusicTrack,
        val ledger: QqListenDurationLedger = QqListenDurationLedger(), var vkey: String = "",
    )
    private val latestTicket = AtomicReference<Ticket?>(null)
    private val client = QqListenDurationClient(context)
    @Volatile private var active: Session? = null

    fun remember(original: MusicTrack, track: MusicTrack, resource: PlaybackSource, session: PlatformSession, revision: Long) {
        if (auth.currentAccount().revision != revision || !auth.isCurrent(session)) return
        val vkey = qqListenVkey(resource.url)
        latestTicket.set(vkey.takeIf(String::isNotBlank)?.let { Ticket(key(session, original), revision, track, it) })
    }

    override suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent) = withContext(Dispatchers.IO) {
        if (event.type == SourcePlaybackSessionEventType.START) {
            if (active?.id == event.sessionId) return@withContext
            val revision = auth.currentAccount().revision
            val session = auth.session()
            val owner = qqCredentialAccountId(session.credential)
            val track = event.track.musicTrack()
            if (owner.isBlank() || qqCredentialMusicKey(session.credential).isBlank() ||
                event.track.track.identity.accountId?.takeIf(String::isNotBlank)?.let { it != owner } == true) {
                active = null
                return@withContext
            }
            val ticket = latestTicket.get()?.takeIf { it.key == key(session, track) && it.revision == revision }
            if (ticket != null) latestTicket.compareAndSet(ticket, null)
            active = Session(event.sessionId, event.track.stableId, owner, revision, ticket?.track ?: track,
                vkey = ticket?.vkey.orEmpty())
        }
        val current = active?.takeIf { it.id == event.sessionId } ?: return@withContext
        if (!isCurrent(current)) { active = null; return@withContext }
        if (!current.ledger.update(event.sequence, event.playedMs)) return@withContext
        if (event.track.track.identity.sourceId != QQ_SOURCE_ID || event.track.stableId != current.stableId) return@withContext
        current.track = current.track.mergeQqTrackMetadata(event.track.musicTrack())
        val ended = event.type == SourcePlaybackSessionEventType.END
        try {
            if (ended || event.type == SourcePlaybackSessionEventType.PAUSE) settle(current, event.occurredAtMs)
        } finally {
            if (ended) active = null
        }
    }

    private suspend fun settle(current: Session, occurredAtMs: Long) {
        val amount = current.ledger.pendingMs()
        if (amount <= 0L) return
        try {
            auth.prepareRequest()
            if (!isCurrent(current)) return
            val session = auth.session()
            val ready = api.listenDurationTrack(current.track, session.credential)
            if (ready.catalogId.toLongOrNull()?.let { it > 0L } != true)
                throw PlatformApiException("QQ时长同步没有返回数字歌曲标识")
            if (!isCurrent(current)) return
            if (current.vkey.isBlank()) current.vkey = api.listenDurationVkey(ready, session.credential, session.deviceId)
            if (current.vkey.isBlank()) throw PlatformApiException("QQ时长同步没有返回真实播放票据")
            val anonymous = client.prepare(session) { isCurrent(current) }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(current)) return
            current.ledger.markAttempted(amount)
            client.report(session, ready, current.vkey, amount, occurredAtMs, anonymous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 时长上报失败不打断播放，记录类型即可，禁止输出账号、票据或原始回包。
            qqDiagnostic("真实收听时长同步失败：${error.javaClass.simpleName}")
        }
    }

    private fun isCurrent(value: Session): Boolean = qqListenSessionAccountMatches(value.account, value.revision,
        auth.session(), auth.currentAccount().revision)
    private fun key(session: PlatformSession, track: MusicTrack): String =
        "${qqCredentialAccountId(session.credential)}|${track.id}"
    fun close() { active = null; latestTicket.set(null) }
}
