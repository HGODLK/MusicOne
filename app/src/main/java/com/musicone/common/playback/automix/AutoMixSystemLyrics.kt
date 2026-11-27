package com.musicone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

private const val AUTOMIX_SYSTEM_LYRIC_POLL_MS = 100L

/**
 * 桌面歌词等系统展示在自然过渡交接后继续停在上一首的最后画面，等下一首开唱再放行；
 * 无过渡时原样透传。heldPlan 由调用方提供，便于单测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<SystemPlaybackPresentation?>.holdAutoMixLyrics(
    heldPlan: () -> AutoMixPlan?,
    nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
): Flow<SystemPlaybackPresentation?> = flow {
    // 每个收集者各自记录上一首最后一份展示，以及保持期间冻结后的画面。
    var latestFrom: SystemPlaybackPresentation? = null
    var frozen: Pair<AutoMixPlan, SystemPlaybackPresentation>? = null
    emitAll(combine(this@holdAutoMixLyrics, AutoMixSession.active, AutoMixSession.lastEnd) { snapshot, _, _ -> snapshot }
        .transformLatest { snapshot ->
            val from = AutoMixSession.active.value?.plan?.from
            if (snapshot != null && from != null && snapshot.key.trackId == from.id) latestFrom = snapshot
            var emittedHeld = false
            while (true) {
                val plan = heldPlan()
                val source = latestFrom
                if (plan == null || snapshot == null || source == null ||
                    source.key.trackId != plan.from.id || snapshot.key.trackId != plan.to.id) {
                    emit(snapshot)
                    return@transformLatest
                }
                if (!emittedHeld) {
                    val held = frozen?.takeIf { it.first === plan }?.second ?: run {
                        val now = nowMs()
                        source.copy(positionMs = source.positionAt(now), sampledAtMs = now, advancing = false)
                            .also { frozen = plan to it }
                    }
                    emit(held)
                    emittedHeld = true
                }
                // 下一首的位置随时间推进，低频复查是否已到开唱点。
                delay(AUTOMIX_SYSTEM_LYRIC_POLL_MS)
            }
        })
}
