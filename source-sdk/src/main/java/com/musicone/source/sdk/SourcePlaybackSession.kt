package com.musicone.source.sdk

/** 由播放服务主动提供的可选能力；默认空值，不启用会话记录或回调。 */
interface SourcePlaybackSessionProvider {
    val playbackSessions: SourcePlaybackSessionObserver? get() = null
}

fun interface SourcePlaybackSessionObserver {
    /** 同一播放器内串行交付；网络、平台协议和结算频率由音源负责。 */
    suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent)
}

enum class SourcePlaybackSessionEventType { START, PAUSE, RESUME, PROGRESS, END }
enum class SourcePlaybackSessionEndReason { COMPLETED, SWITCHED, STOPPED, ERROR, RELEASED }

/** playedMs 是本次会话累计的真实墙钟收听毫秒，不能当作每次新增量。 */
data class SourcePlaybackSessionEvent(
    val sessionId: String,
    val sequence: Long,
    val track: SourceTrackDetails,
    val type: SourcePlaybackSessionEventType,
    val playedMs: Long,
    val positionMs: Long,
    val occurredAtMs: Long,
    val endReason: SourcePlaybackSessionEndReason? = null,
)
