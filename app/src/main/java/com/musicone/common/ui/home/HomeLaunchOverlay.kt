package com.musicone

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver


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
    val covering = launch.covering
    LaunchedEffect(launch) {
        // 首帧准备好即展示页面，不再等待音源、网络内容或封面。
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }

    AnimatedVisibility(
        visible = covering,
        exit = fadeOut(musicMotion(responseMillis = 420)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clearAndSetSemantics { stateDescription = "正在准备首页" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
    }
}
