package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest

/** 管理横屏时钟动画任务的所有权，防止旧任务结束时误清状态或中断后卡在半路。 */
internal class PhoneClockHandoff {
    var running by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private var owner = 0

    fun begin(): Int { running = true; return ++owner }
    fun finish(token: Int): Boolean {
        if (token != owner) return false
        running = false
        return true
    }

    fun recoverIfIdle(progress: Float, velocity: Float, exposure: Float,
        endpointReady: Boolean, stalled: Boolean, nativeDrawing: Boolean): Boolean {
        if (running || phoneClockDisplayReady(progress, velocity, exposure, endpointReady, stalled, nativeDrawing)) return false
        revision++
        return true
    }
}

internal fun phoneClockDisplayReady(progress: Float, velocity: Float, exposure: Float,
    endpointReady: Boolean, stalled: Boolean, nativeDrawing: Boolean): Boolean =
    progress == 1f && velocity == 0f && exposure == 1f && (nativeDrawing || endpointReady && !stalled)

private data class PhoneClockRecoveryCheck(val request: PhoneClockTransitionRequest,
    val track: Pair<MusicSource, String>, val running: Boolean, val turning: Boolean,
    val endpointReady: Boolean)

/** 监听切歌和转向结束等边界状态，如果时钟动画意外卡在半路则自动触发恢复。 */
@Composable
internal fun PhoneClockHandoffRecovery(handoff: PhoneClockHandoff, request: PhoneClockTransitionRequest,
    track: MusicTrack, turn: PhonePlayerTurn?, flight: PhoneClockFlight,
    exposure: () -> Float, nativeDrawing: () -> Boolean) {
    val latestRequest by rememberUpdatedState(request)
    val latestTrack by rememberUpdatedState(track.source to track.id)
    val latestExposure by rememberUpdatedState(exposure)
    val latestNative by rememberUpdatedState(nativeDrawing)
    LaunchedEffect(handoff, turn) {
        snapshotFlow {
            PhoneClockRecoveryCheck(latestRequest, latestTrack, handoff.running, turn?.isTurning == true,
                turn?.endpointReady("clock", latestRequest.rotation) != false)
        }.collectLatest { check ->
            if (check.running || check.request.target != 1f || check.request.preparing || check.request.fading)
                return@collectLatest
            // 先等两帧让正常动画启动，避免切歌重组同帧时误触发兜底恢复。
            repeat(2) { withFrameNanos { } }
            if (latestRequest != check.request) return@collectLatest
            handoff.recoverIfIdle(flight.progress.value, flight.velocity, latestExposure(),
                turn?.endpointReady("clock", check.request.rotation) != false,
                turn?.clockHandoffStalled(check.request.rotation) == true, latestNative())
        }
    }
}
