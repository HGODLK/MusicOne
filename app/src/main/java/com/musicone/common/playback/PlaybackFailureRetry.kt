package com.musicone

import com.musicone.source.sdk.SourceOperationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 当前歌取源统一重试策略：首取加两次重试，间隔两秒。
 * 降档时每一档单独限时三秒，超时就降下一档；整次尝试另有宽松兜底时限，只防止卡死。
 */
internal data class PlaybackRetryPolicy(
    val attempts: Int = 3,
    val intervalMs: Long = 2_000L,
    val tierTimeoutMs: Long = 3_000L,
    val attemptTimeoutMs: Long = 30_000L,
)

/** 连续多少首无法播放后暂停，避免断网或账号失效时整条队列被刷完。 */
internal const val PLAYBACK_CONSECUTIVE_FAILURE_LIMIT = 3

/** 播放器中途出错时的续播重试次数与间隔，与取源重试保持一致。 */
internal const val PLAYER_ERROR_RENEW_LIMIT = 2
internal const val PLAYER_ERROR_RENEW_DELAY_MS = 2_000L

/** 单次尝试超过时限；与普通失败区分，便于提示“加载超时”。 */
internal class PlaybackResolveTimeoutException : Exception("音源加载超时")

/**
 * 一首歌本次起播的取源记录，经协程上下文传给解析器的降档循环：
 * 已确认没有地址的档位重试时直接跳过；整条降档都被明确拒绝时不再重试。
 */
internal class PlaybackResolveSession(val tierTimeoutMs: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlaybackResolveSession>

    // minSdk 23 没有 ConcurrentHashMap.newKeySet（API 24），用等价的并发集合。
    private val missed: MutableSet<AudioQuality> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var uncertain = false
    /** 本轮所有候选档位都被音源明确拒绝，且没有网络错误或超时，重试也不会有不同结果。 */
    @Volatile var definitive = false
        private set

    fun beginAttempt() { uncertain = false; definitive = false }
    fun skips(quality: AudioQuality) = quality in missed
    fun missed(quality: AudioQuality) { missed += quality }
    fun uncertain() { uncertain = true }
    /** 降档结束且未拿到地址时调用；候选为空说明全部档位此前已确认没有地址。 */
    fun finish(candidates: List<AudioQuality>) {
        definitive = !uncertain && candidates.all { it in missed }
    }
}

/**
 * 单档失败是否属于音源明确答复“没有地址”：登录、风控、网络异常都可能是暂时的，不算。
 * 各音源对无地址的错误码不统一，这里只认响应级别的拒绝。
 */
internal fun Throwable.isDefinitiveTierMiss(): Boolean = when {
    stopsSourcePlaybackFallback() -> false
    generateSequence(this) { it.cause }.any { it is java.io.IOException } -> false
    this is SourceOperationException -> code == "TRACK_UNAVAILABLE" || code == "SOURCE_REQUEST_FAILED"
    else -> this is TrackUnavailableException || this is PlatformApiException
}

/**
 * 按策略重复取源；任何错误都重试，只有 [retryable] 明确拒绝时才立即放弃。
 * [extraTimeoutMs] 用于首轮切歌防抖等待，这段等待不计入兜底时限。
 */
internal suspend fun <T> retryPlaybackResolve(
    policy: PlaybackRetryPolicy,
    extraTimeoutMs: (attempt: Int) -> Long = { 0L },
    retryable: (Throwable) -> Boolean = { true },
    shouldContinue: () -> Boolean = { true },
    beforeAttempt: () -> Unit = {},
    block: suspend (attempt: Int) -> T,
): T {
    var last: Throwable = PlaybackResolveTimeoutException()
    for (attempt in 0 until policy.attempts.coerceAtLeast(1)) {
        if (attempt > 0) {
            delay(policy.intervalMs)
            if (!shouldContinue()) throw CancellationException("取源请求已过期")
        }
        beforeAttempt()
        try {
            return withTimeout(policy.attemptTimeoutMs + extraTimeoutMs(attempt)) { block(attempt) }
        } catch (timeout: TimeoutCancellationException) {
            last = PlaybackResolveTimeoutException()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            last = error
            if (!retryable(error)) break
        }
    }
    throw last
}
