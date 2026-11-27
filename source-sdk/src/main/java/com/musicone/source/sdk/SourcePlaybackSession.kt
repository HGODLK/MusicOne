package com.musicone.source.sdk

/** 可选的播放会话上报能力；默认为 null（不记录也不回调）。 */
interface SourcePlaybackSessionProvider {
    val playbackSessions: SourcePlaybackSessionObserver? get() = null
}

fun interface SourcePlaybackSessionObserver {
    /** 同一播放器内按顺序回调；具体上报协议、网络请求和频率由音源自行控制。 */
    suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent)
}

enum class SourcePlaybackSessionEventType { START, PAUSE, RESUME, PROGRESS, END }
enum class SourcePlaybackSessionEndReason { COMPLETED, SWITCHED, STOPPED, ERROR, RELEASED }

/** playedMs 是本次会话累计的实际收听时长（毫秒），不是单次增量。 */
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
