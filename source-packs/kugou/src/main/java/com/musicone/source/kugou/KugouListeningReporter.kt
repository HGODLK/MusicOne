package com.musicone.source.kugou

import android.util.Log
import com.musicone.source.sdk.SourcePlaybackSessionEndReason
import com.musicone.source.sdk.SourcePlaybackSessionEvent
import com.musicone.source.sdk.SourcePlaybackSessionEventType
import com.musicone.source.sdk.SourcePlaybackSessionObserver
import com.musicone.source.sdk.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 负责上报酷狗听歌时长和 CSCC 播放事件（最近播放仍由 reportPlayed 单独处理）。 */
internal class KugouListeningReporter(
    private val capture: () -> KugouAccountRequest,
    private val check: (KugouAccountRequest) -> Unit,
    private val client: KugouListeningService,
    private val failed: (String) -> Unit = { stage -> runCatching { Log.w("KugouListen", "酷狗听歌同步未完成：$stage") }; Unit },
) : SourcePlaybackSessionObserver {
    private data class Session(val id: String, val owner: KugouAccountRequest, val track: TrackIdentity,
        var sequence: Long, var playedMs: Long, var submittedMs: Long = 0, var csccSong: Long? = null)
    private val mutex = Mutex()
    private var active: Session? = null
    private var lastEnded: String? = null

    override suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent) {
        mutex.withLock {
            if (event.playedMs < 0 || event.track.track.identity.sourceId != KUGOU_SOURCE_ID) return@withLock
            if (event.type == SourcePlaybackSessionEventType.START) {
                if (active?.id == event.sessionId || lastEnded == event.sessionId) return@withLock
                active = null
                val owner = attempt("读取登录会话") { capture() } ?: return@withLock
                val account = runCatching { owner.cookie.requiredKugouSession() }.getOrNull() ?: return@withLock
                if (event.track.track.identity.accountId?.let { it != account.userId } == true) return@withLock
                val session = Session(event.sessionId, owner, event.track.track.identity, event.sequence, event.playedMs)
                active = session
                attempt("开始事件") {
                    check(owner)
                    val song = event.track.track.opaqueData["catalogId"]?.toLongOrNull()?.takeIf { it > 0 } ?: return@attempt
                    client.playback(owner.cookie, song, true, 0, false, event.occurredAtMs) { check(owner) }
                    session.csccSong = song
                }
                return@withLock
            }
            val session = active?.takeIf { it.id == event.sessionId } ?: return@withLock
            if (event.sequence <= session.sequence || event.playedMs < session.playedMs ||
                event.track.track.identity != session.track) return@withLock
            session.sequence = event.sequence
            session.playedMs = event.playedMs
            if (attempt("核对登录会话") { check(session.owner); true } != true) {
                active = null
                lastEnded = session.id
                return@withLock
            }
            if (event.type == SourcePlaybackSessionEventType.PAUSE || event.type == SourcePlaybackSessionEventType.END) {
                settle(session)
            }
            if (event.type == SourcePlaybackSessionEventType.END) {
                // 先把 active 置空，避免失败或重复回调导致同一段时长被报两次
                active = null
                lastEnded = session.id
                attempt("结束事件") {
                    check(session.owner)
                    // 之前没成功报过开始事件，就不单独发结束事件
                    val song = session.csccSong ?: return@attempt
                    client.playback(session.owner.cookie, song, false, event.playedMs,
                        event.endReason == SourcePlaybackSessionEndReason.COMPLETED, event.occurredAtMs) { check(session.owner) }
                }
            }
        }
    }

    private suspend fun settle(session: Session) {
        val seconds = (session.playedMs - session.submittedMs) / 1_000
        if (seconds <= 0) return
        attempt("真实时长") {
            check(session.owner)
            // 每次结算前先查服务端当前的累计基线，再提交本次真实新增秒数
            val baseline = client.baseline(session.owner.cookie) { check(session.owner) }
            check(session.owner)
            // 发请求前先把这段时长记为已提交，就算超时也不重复补发
            session.submittedMs += seconds * 1_000
            client.sync(session.owner.cookie, baseline, seconds) { check(session.owner) }
        }
    }

    private suspend fun <T> attempt(stage: String, action: suspend () -> T): T? = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failed(stage); null }
}
