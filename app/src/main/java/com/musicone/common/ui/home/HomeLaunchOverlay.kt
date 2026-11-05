package com.musicone

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription

@Stable
internal class HomeLaunchState(covering: Boolean = true) {
    var covering by mutableStateOf(covering)
}

@Composable
internal fun rememberHomeLaunchState(): HomeLaunchState = rememberSaveable(
    saver = Saver(save = { it.covering }, restore = { HomeLaunchState(it) }),
) { HomeLaunchState() }

@Composable
internal fun HomeLaunchOverlay(
    launch: HomeLaunchState,
) {
    LaunchedEffect(launch) {
        // 首帧准备好即展示页面，不再等待音源、网络内容或封面；播放页预热也只到这里。
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }
    val animated = remember { HomeLaunchGate.process.takeAnimation() }
    if (animated) HomeLaunchAnimation()
    else if (launch.covering) Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .homeLaunchCover { },
    )
}

@Composable
private fun HomeLaunchAnimation() {
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val mark = remember { HomeLaunchMark() }
    val clock = remember { HomeLaunchClock(HomeLaunchGate.process) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (!finished && !clock.finished) {
            withFrameNanos { now ->
                clock.advance(now - last)
                last = now
            }
        }
        finished = true
    }
    if (finished) return
    // 开始淡出后首页就能操作，遮罩不再拦截触摸。
    val covering by remember { derivedStateOf { clock.elapsedMillis < HomeLaunchTimeline.ExitStartMillis } }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (covering) Modifier.homeLaunchCover { finished = true } else Modifier)
            .drawBehind { mark.draw(this, clock.elapsedMillis, background, ink) },
    )
}

/** 拦截遮罩下方的触摸，按下时通知调用方。 */
private fun Modifier.homeLaunchCover(onPress: () -> Unit): Modifier = this
    .clearAndSetSemantics { stateDescription = "正在准备首页" }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            onPress()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
