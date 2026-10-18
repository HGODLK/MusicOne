package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive

/** 常驻只改变关闭态的准备方式；持续刷新由独立构建开关控制。 */
internal data class LyricResidency(
    val retained: Boolean = false,
    val available: Boolean = false,
    val closed: Boolean = false,
    val continuous: Boolean = false,
) {
    val prepareClosed get() = retained && available && closed
    val keepRefreshing get() = prepareClosed && continuous
    val pauseWords get() = retained && closed && !keepRefreshing

    companion object { val None = LyricResidency() }
}

@Composable
internal fun rememberLyricResidency(retained: Boolean, visible: Boolean, closed: Boolean): LyricResidency {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val navigation = LocalEntityNavigation.current
    val menu = LocalQqPlaylistSongMenu.current
    val quality = LocalPlayerQualityMenu.current
    val covered = navigation?.pages?.isNotEmpty() == true || menu?.expanded == true || quality?.expanded == true
    return LyricResidency(retained,
        visible && focused && !covered && state.isAtLeast(Lifecycle.State.STARTED) && !LocalPlayerPrewarming.current,
        closed, BuildConfig.MUSICONE_RESIDENT_LYRIC_REFRESH)
}

/** 关闭态直接保持正式阅读坐标，只在换句、改窗或重新可见时更新。 */
@Composable
internal fun LyricResidentAlignmentEffect(
    residency: LyricResidency,
    pane: LyricWindowPane,
    current: Int,
    allowed: Boolean,
    typography: LyricTypography,
    onAligned: () -> Unit,
) {
    val viewport by remember(pane) {
        derivedStateOf { pane.listState.layoutInfo.let { it.viewportSize to it.viewportStartOffset } }
    }
    val latestAligned by rememberUpdatedState(onAligned)
    LyricAnimationEffect(pane, residency.prepareClosed, current, allowed, viewport, typography) {
        if (!residency.prepareClosed || !allowed || viewport.first.height <= 0) return@LyricAnimationEffect
        pane.playbackStep.reset(current)
        pane.listState.scrollToItem(current)
        latestAligned()
    }
}

/** 对照包只在前台播放页的关闭态持续录制真实歌词，不输出额外像素。 */
@Composable
internal fun Modifier.lyricResidentRefresh(residency: LyricResidency): Modifier {
    var frame by remember { mutableLongStateOf(0L) }
    LyricAnimationEffect(residency.keepRefreshing) {
        if (residency.keepRefreshing) while (isActive) withFrameNanos { frame = it }
    }
    return if (residency.keepRefreshing) drawWithContent {
        @Suppress("UNUSED_VARIABLE") val currentFrame = frame
        drawContent()
    } else this
}
