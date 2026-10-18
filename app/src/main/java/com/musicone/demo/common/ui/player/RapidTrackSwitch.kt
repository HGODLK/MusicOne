package com.musicone.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val RAPID_TRACK_SETTLE_DELAY_MS = 400L

internal enum class RapidTrackSwitchPhase { SINGLE, BROWSING, SETTLING }

internal data class RapidTrackSwitchPresentation(
    val track: MusicTrack,
    val direction: TrackTransitionDirection,
    val phase: RapidTrackSwitchPhase,
    val lyricsAvailable: Boolean,
    val token: Long,
    val lyricsResolved: Boolean = lyricsAvailable,
)

/** QQ 可见歌词的单次切歌也先交接；连续切歌只提交完成收束的最终歌曲。 */
internal class RapidTrackSwitch(
    private val scope: CoroutineScope,
    private val cachedLyrics: suspend (MusicTrack) -> List<TimedLyric>,
    private val neighbor: (MusicTrack?, Boolean, Boolean) -> MusicTrack?,
    private val commit: (MusicTrack, TrackTransitionDirection) -> Unit,
    private val direct: (Boolean) -> Unit,
    private val beginBrowsing: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitForSingleLyrics: () -> Boolean = { false },
) {
    private val mutablePresentation = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
    val presentation = mutablePresentation.asStateFlow()
    private var settleJob: Job? = null
    private var cacheJob: Job? = null
    private var token = 0L
    private var lastDirectInputMs: Long? = null
    private val lyricSettlements = MutableStateFlow<Map<Any, Long?>>(emptyMap())
    private val presentationActive = MutableStateFlow(true)

    fun setPresentationActive(active: Boolean) {
        presentationActive.value = active
        if (!active) lyricSettlements.value = emptyMap()
    }

    fun attachLyrics(owner: Any) {
        if (presentationActive.value) lyricSettlements.update { it + (owner to null) }
    }

    fun detachLyrics(owner: Any) {
        lyricSettlements.update { it - owner }
    }

    fun lyricsSettled(owner: Any, requestToken: Long) {
        if (mutablePresentation.value?.token != requestToken) return
        lyricSettlements.update { if (owner in it) it + (owner to requestToken) else it }
    }

    fun request(next: Boolean) {
        val previous = mutablePresentation.value
        val inputMs = nowMs()
        if (previous == null && waitForSingleLyrics() && lyricSettlements.value.isNotEmpty()) {
            val target = neighbor(null, next, true) ?: return
            preview(target, if (next) TrackTransitionDirection.NEXT else TrackTransitionDirection.PREVIOUS, false)
            return
        }
        if (previous == null && lastDirectInputMs?.let {
                inputMs - it in 0..RAPID_TRACK_SETTLE_DELAY_MS
            } != true) {
            // 直切内部会清理旧请求，识别窗口必须在直切返回后记录。
            direct(next)
            lastDirectInputMs = inputMs
            return
        }
        val target = neighbor(previous?.track, next, previous == null) ?: return
        val direction = if (next) TrackTransitionDirection.NEXT else TrackTransitionDirection.PREVIOUS
        preview(target, direction, true)
    }

    /** 自然切歌沿用已选出的目标，不能重新抽取随机队列或消费播放历史。 */
    fun handoff(track: MusicTrack, direction: TrackTransitionDirection): Boolean {
        if (!waitForSingleLyrics() || lyricSettlements.value.isEmpty()) return false
        preview(track, direction, false)
        return true
    }

    private fun preview(target: MusicTrack, direction: TrackTransitionDirection, continuous: Boolean) {
        val previous = mutablePresentation.value
        val requestToken = ++token
        settleJob?.cancel()
        cacheJob?.cancel()
        if (previous == null) beginBrowsing()
        mutablePresentation.value = RapidTrackSwitchPresentation(
            track = target,
            direction = direction,
            phase = if (continuous) RapidTrackSwitchPhase.BROWSING else RapidTrackSwitchPhase.SINGLE,
            lyricsAvailable = target.lyrics.isNotEmpty(),
            token = requestToken,
        )
        if (target.lyrics.isEmpty()) {
            cacheJob = scope.launch {
                val lyrics = try { cachedLyrics(target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                if ((lyrics.isNotEmpty() || target.source.supportsLibraryEditing) && mutablePresentation.value?.token == requestToken) {
                    mutablePresentation.update { current ->
                        current?.copy(track = target.copy(lyrics = lyrics), lyricsAvailable = lyrics.isNotEmpty(), lyricsResolved = true)
                    }
                }
            }
        }
        settleJob = scope.launch {
            if (continuous && presentationActive.value) {
                withTimeoutOrNull(RAPID_TRACK_SETTLE_DELAY_MS) { presentationActive.first { !it } }
            }
            // 缓存只服务可见歌词；退到后台后不等待磁盘或帧时钟，直接提交最终目标。
            val lookup = cacheJob
            if (lookup != null && lyricSettlements.value.isNotEmpty()) coroutineScope {
                val hidden = async { lyricSettlements.first { it.isEmpty() } }
                try {
                    select<Unit> {
                        lookup.onJoin { }
                        hidden.onAwait { }
                    }
                } finally { hidden.cancel() }
            }
            if (lyricSettlements.value.isEmpty()) lookup?.cancel()
            val final = mutablePresentation.value?.takeIf { it.token == requestToken } ?: return@launch
            val settled = final.copy(phase = RapidTrackSwitchPhase.SETTLING)
            mutablePresentation.value = settled
            // 没有可见歌词页时无需等待；退出、切后台会注销等待方，不用超时猜动画时长。
            lyricSettlements.first { owners -> owners.values.all { it == requestToken } }
            if (mutablePresentation.value?.token != requestToken) return@launch
            commit(settled.track, settled.direction)
            if (mutablePresentation.value?.token == requestToken) {
                mutablePresentation.value = null
                lastDirectInputMs = null
            }
        }
    }

    fun cancel() {
        token++
        settleJob?.cancel()
        cacheJob?.cancel()
        settleJob = null
        cacheJob = null
        lastDirectInputMs = null
        mutablePresentation.value = null
    }
}

internal fun rapidQueueNeighbor(
    state: MusicOneUiState,
    anchor: MusicTrack?,
    next: Boolean,
): MusicTrack? {
    val current = anchor ?: state.currentTrack ?: return null
    val queue = state.queue
    if (queue.size == 1) return queue.first()
    if (queue.isEmpty()) return null
    if (state.qqRadioActive) {
        return if (next) nextQqRadioTrack(queue, current.id) else previousQqRadioTrack(queue, current.id)
    }
    val index = queue.indexOfFirst { it.id == current.id }
    if (index < 0) return null
    return queue[if (next) (index + 1) % queue.size else (index - 1 + queue.size) % queue.size]
}
