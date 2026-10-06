package com.musicone.demo

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class RapidLyricMotion(
    val token: Long,
    val phase: RapidTrackSwitchPhase,
    val trackId: String,
    val source: MusicSource,
    val lyricsAvailable: Boolean,
)

private fun RapidTrackSwitchPresentation.motion() =
    RapidLyricMotion(token, phase, track.id, track.source, lyricsAvailable)

/** 只在歌词可见且前台时等待实际收束，后台和封面页不阻塞最终起播。 */
@Composable
internal fun rememberRapidLyricHandoff(
    switch: RapidTrackSwitch,
    enabled: Boolean,
    settled: (RapidLyricMotion) -> Boolean,
): State<RapidLyricMotion?> {
    val owner = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motion = remember(switch) {
        switch.presentation.map { it?.motion() }.distinctUntilChanged()
    }.collectAsStateWithLifecycleFrom(switch.presentation) { it?.motion() }
    LaunchedEffect(switch, enabled, lifecycle) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            switch.attachLyrics(owner)
            try { awaitCancellation() } finally { switch.detachLyrics(owner) }
        }
    }
    val latestSettled by rememberUpdatedState(settled)
    LaunchedEffect(switch, enabled, motion.value) {
        val request = motion.value ?: return@LaunchedEffect
        if (!enabled || request.phase != RapidTrackSwitchPhase.SETTLING) return@LaunchedEffect
        do {
            snapshotFlow { latestSettled(request) }.first { it }
            // 用宿主绘制时钟等完成帧显示；期间发生新的窗口准备时继续等候。
            withFrameNanos { }
            withFrameNanos { }
        } while (!latestSettled(request))
        switch.lyricsSettled(owner, request.token)
    }
    return motion
}
