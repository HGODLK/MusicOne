package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest

/** 显示任务单独登记所有权，旧入口的收尾不能清除新入口，也不能留下无人续接的半程。 */
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

/** 只观察切歌、任务结束和转向交接边界，正常时分秒及飞行帧不触发重启。 */
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
            // 让本轮正常入口先取得所有权，避免切歌重组与入口启动同帧时抢断原动画。
            repeat(2) { withFrameNanos { } }
            if (latestRequest != check.request) return@collectLatest
            handoff.recoverIfIdle(flight.progress.value, flight.velocity, latestExposure(),
                turn?.endpointReady("clock", check.request.rotation) != false,
                turn?.clockHandoffStalled(check.request.rotation) == true, latestNative())
        }
    }
}
