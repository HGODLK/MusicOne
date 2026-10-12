package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class MiniPlayerSwipe(private val scope: CoroutineScope) {
    var outgoing by mutableStateOf<MusicTrack?>(null)
    var incoming by mutableStateOf<MusicTrack?>(null)
    var amount by mutableFloatStateOf(0f)
    var next by mutableStateOf(true)
    var settling by mutableStateOf(false)
    private var settleJob: Job? = null
    private var committedTargetKey: String? = null
    private var targetPresented = false
    private var targetBaseArtworkDrawn = false
    private var targetOverlayArtworkDrawn = false
    private var settleFinished = false
    val active get() = incoming != null && outgoing != null

    fun reset() {
        settleJob?.cancel()
        settleJob = null
        clearState()
    }

    private fun clearState() {
        Snapshot.withMutableSnapshot {
            incoming = null
            outgoing = null
            amount = 0f
            settling = false
            committedTargetKey = null
            targetPresented = false
            targetBaseArtworkDrawn = false
            targetOverlayArtworkDrawn = false
            settleFinished = false
        }
    }

    fun finish(commit: Boolean, onCommit: (MusicTrack, Boolean) -> Unit, initialVelocity: Float = 0f) {
        val target = incoming ?: return reset()
        settling = true
        if (commit) {
            committedTargetKey = target.visualKey
            onCommit(target, next)
        }
        settleJob = scope.launch {
            val progress = Animatable(amount)
            val velocity = initialVelocity.coerceIn(0f, 3.5f)
            progress.animateTo(if (commit) 1f else 0f, musicMotion(200), initialVelocity = velocity) { amount = value }
            if (commit) {
                settleFinished = true
                finishHandoffWhenReady()
            } else {
                reset()
            }
        }
    }

    fun onTrackPresented(track: MusicTrack) {
        if (!active) return
        val targetKey = committedTargetKey
        when {
            targetKey == null && outgoing?.visualKey != track.visualKey -> reset()
            targetKey != null && targetKey != track.visualKey -> reset()
            targetKey == track.visualKey -> {
                if (incoming?.artworkIdentity() != track.artworkIdentity()) {
                    // 预览可能只有占位图；真实歌曲补全封面后，预览层也必须更新完再交接。
                    targetBaseArtworkDrawn = false
                    targetOverlayArtworkDrawn = false
                }
                incoming = track
                targetPresented = true
                finishHandoffWhenReady()
            }
        }
    }

    fun onTargetBaseArtworkDrawn(track: MusicTrack, artwork: PlayerArtworkFrame) {
        if (!active || artwork.identity != track.artworkIdentity()) return
        if (committedTargetKey == track.visualKey) {
            targetBaseArtworkDrawn = true
            finishHandoffWhenReady()
        }
    }

    fun onTargetOverlayArtworkDrawn(track: MusicTrack, artwork: PlayerArtworkFrame) {
        if (!active || artwork.identity != track.artworkIdentity()) return
        if (committedTargetKey == track.visualKey) {
            targetOverlayArtworkDrawn = true
            finishHandoffWhenReady()
        }
    }

    private fun finishHandoffWhenReady() {
        if (!settleFinished || !targetPresented || !targetBaseArtworkDrawn || !targetOverlayArtworkDrawn) return
        // 手势层和主内容都已绘制目标封面，此处撤层不会中断未缓存封面的淡入。
        settleJob = null
        clearState()
    }
}

private val MusicTrack.visualKey: String
    get() = "${source.name}:$id"

@Composable
internal fun rememberMiniPlayerSwipe(track: MusicTrack): MiniPlayerSwipe {
    val scope = rememberCoroutineScope()
    val swipe = remember { MiniPlayerSwipe(scope) }
    LaunchedEffect(
        track.source,
        track.id,
        track.artworkIdentity(),
        track.playerTextPresentation(),
    ) { swipe.onTrackPresented(track) }
    return swipe
}

internal fun Modifier.miniPlayerSwipe(
    swipe: MiniPlayerSwipe, track: MusicTrack, enabled: Boolean,
    preview: (Boolean) -> MusicTrack?, commit: (MusicTrack, Boolean) -> Unit,
): Modifier = pointerInput(track.id, enabled) {
    if (!enabled) return@pointerInput
    val threshold = 48 * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (swipe.settling) return@awaitEachGesture
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        val release = GestureReleaseVelocity(down.uptimeMillis, density)
        var total = Offset.Zero
        var distance = 0f
        var dragging = false
        fun move(delta: Float) {
            distance += delta
            val next = release.effectiveDistancePx < 0f
            if (swipe.incoming == null || swipe.next != next) {
                swipe.incoming = preview(next)
                swipe.outgoing = track
                swipe.next = next
            }
            swipe.amount = (abs(distance) / threshold).coerceIn(0f, 1f)
        }
        try {
            var start: androidx.compose.ui.input.pointer.PointerInputChange? = null
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed || currentEvent.changes.count { it.pressed } > 1) break
                val delta = change.position - change.previousPosition
                total += delta
                tracker.addPosition(change.uptimeMillis, change.position)
                release.moved(change.uptimeMillis, delta.x)
                when (verticalSlopIntent(total.y, total.x, viewConfiguration.touchSlop)) {
                    -1 -> break
                    1 -> {
                        swipe.reset()
                        dragging = true
                        move(total.x - if (total.x > 0f) viewConfiguration.touchSlop else -viewConfiguration.touchSlop)
                        change.consume()
                        start = change
                        break
                    }
                }
            }
            if (start != null) {
                val completed = horizontalDrag(start.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.position.x - change.previousPosition.x
                    release.moved(change.uptimeMillis, delta)
                    move(delta)
                    change.consume()
                }
                val now = currentEvent.changes.firstOrNull { it.id == start.id }?.uptimeMillis ?: start.uptimeMillis
                val velocityX = release.released(now, tracker.calculateVelocity().x, completed)
                val fling = abs(release.effectiveDistancePx) / density >= 12f &&
                    release.effectiveDistancePx * velocityX > 0f && abs(velocityX) / density >= 240f
                val commitSwipe = completed && (abs(distance) >= threshold || fling)
                swipe.finish(commitSwipe, commit, (abs(velocityX) / threshold).coerceIn(0f, 3.5f))
                dragging = false
            }
        } finally {
            if (dragging && !swipe.settling) swipe.finish(false, commit)
        }
    }
}
