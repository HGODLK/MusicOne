package com.musicone.sourceintegration

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.musicone.MusicTrack
import com.musicone.source.sdk.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** 只为主动提供回调的音源计时；复用播放器状态和现有秒级进度通知。 */
internal class SourcePlaybackSessionReporter(
    private val observerFor: (MusicTrack) -> SourcePlaybackSessionObserver?,
    private val elapsed: () -> Long,
    private val wallTime: () -> Long,
    private val newId: () -> String,
    private val dispatch: (SourcePlaybackSessionObserver, SourcePlaybackSessionEvent) -> Unit,
    private val closeDispatch: () -> Unit = {},
) {
    constructor(context: Context) : this(context, SessionDelivery())
    private constructor(context: Context, delivery: SessionDelivery) : this(
        { track -> (SourcePackageController.get(context).running(track.source.id)?.entry?.mediaPlayback
            as? SourcePlaybackSessionProvider)?.playbackSessions },
        SystemClock::elapsedRealtime, System::currentTimeMillis, { UUID.randomUUID().toString() },
        delivery::send, delivery::close,
    )

    private class Session(val id: String, val observer: SourcePlaybackSessionObserver, var track: MusicTrack,
        var lastMs: Long, var positionMs: Long) {
        var advancing = true
        var playedMs = 0L
        var sequence = 0L
        var mappedTrack: MusicTrack? = null
        var details: SourceTrackDetails? = null
    }
    private var active: Session? = null
    private var disabledTrack: Pair<String, String>? = null
    private fun MusicTrack.key() = source.id to id

    fun observe(track: MusicTrack, advancing: Boolean, positionMs: Long,
        completed: Boolean = false, progress: Boolean = false) {
        if (active?.track?.key()?.let { it != track.key() } == true) finish(SourcePlaybackSessionEndReason.SWITCHED)
        var session = active
        if (session == null) {
            if (!advancing || completed || disabledTrack == track.key()) return
            val observer = try { observerFor(track) } catch (_: Exception) { null }
            if (observer == null) { disabledTrack = track.key(); return }
            session = Session(newId(), observer, track, elapsed(), positionMs.coerceAtLeast(0))
            active = session
            emit(session, SourcePlaybackSessionEventType.START)
            return
        }
        record(session)
        session.track = track
        session.positionMs = positionMs.coerceAtLeast(0)
        if (completed) { finish(SourcePlaybackSessionEndReason.COMPLETED); return }
        val changed = session.advancing != advancing
        session.advancing = advancing
        when {
            changed -> emit(session, if (advancing) SourcePlaybackSessionEventType.RESUME else SourcePlaybackSessionEventType.PAUSE)
            advancing && progress -> emit(session, SourcePlaybackSessionEventType.PROGRESS)
        }
    }

    fun pause(positionMs: Long) {
        val session = active ?: return
        observe(session.track, false, positionMs)
    }

    fun prepare(track: MusicTrack, positionMs: Long, continuing: Boolean) {
        if (continuing && active?.track?.key() == track.key()) pause(positionMs)
        else finish(SourcePlaybackSessionEndReason.SWITCHED, positionMs)
        disabledTrack = null
    }

    fun finish(reason: SourcePlaybackSessionEndReason, positionMs: Long? = null) {
        val session = active ?: return
        record(session)
        session.advancing = false
        positionMs?.let { session.positionMs = it.coerceAtLeast(0) }
        active = null
        emit(session, SourcePlaybackSessionEventType.END, reason)
    }

    fun close(positionMs: Long) {
        finish(SourcePlaybackSessionEndReason.RELEASED, positionMs)
        closeDispatch()
    }

    private fun record(session: Session) {
        val now = elapsed()
        if (session.advancing) session.playedMs += (now - session.lastMs).coerceAtLeast(0)
        session.lastMs = now
    }

    private fun emit(session: Session, type: SourcePlaybackSessionEventType, reason: SourcePlaybackSessionEndReason? = null) {
        // 曲目未变化时复用资料快照，不在每秒通知中重复转换歌词与音质列表。
        if (session.mappedTrack !== session.track) {
            session.details = session.track.toSourceTrack()
            session.mappedTrack = session.track
        }
        dispatch(session.observer, SourcePlaybackSessionEvent(session.id, ++session.sequence,
            requireNotNull(session.details), type, session.playedMs, session.positionMs, wallTime(), reason))
    }
}

/** 网络回调离开播放线程串行运行；释放播放器时先排空末尾结算，再关闭工作协程。 */
private class SessionDelivery {
    private data class Delivery(val observer: SourcePlaybackSessionObserver, val event: SourcePlaybackSessionEvent)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Delivery>(Channel.UNLIMITED)
    private var started = false
    fun send(observer: SourcePlaybackSessionObserver, event: SourcePlaybackSessionEvent) {
        if (!started) {
            started = true
            scope.launch {
                try {
                    for (item in queue) try { item.observer.onPlaybackSession(item.event) }
                    catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (failure: Exception) { Log.w("SourcePlaybackSession", "播放会话回调失败：${failure.javaClass.simpleName}") }
                } finally { scope.cancel() }
            }
        }
        queue.trySend(Delivery(observer, event))
    }
    fun close() { queue.close(); if (!started) scope.cancel() }
}
