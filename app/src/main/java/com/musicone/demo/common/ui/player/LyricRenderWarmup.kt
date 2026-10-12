package com.musicone.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.channels.Channel

internal class LyricRenderWarmupState {
    var requested = false
        private set
    var ready by mutableStateOf(false)
        private set
    fun begin() { requested = true }
    fun complete() { ready = true }
}

internal val LocalLyricRenderWarmup = staticCompositionLocalOf<LyricRenderWarmupState?> { null }

/** 只在启动遮罩下重放少量真实歌词行，覆盖字形、逐字遮罩和模糊图层的首次绘制。 */
@Composable
internal fun LyricRenderWarmup(presentation: LyricPresentation?, positionMs: Long, typography: LyricTypography) {
    val readiness = LocalLyricRenderWarmup.current ?: return
    val lines = remember(presentation?.lyrics, presentation?.id) {
        val source = presentation?.lyrics.orEmpty()
        if (source.isEmpty()) lyricWarmupSamples else {
            val start = activeLyric(source, positionMs).coerceIn(0, source.lastIndex)
            val selected = source.drop(start).take(3)
            // 首句可能是无逐字信息的标题；仅为内部绘制副本补一个词段，提前走过遮罩路径。
            if (selected.any { it.words.isNotEmpty() }) selected else selected.mapIndexed { index, line ->
                if (index == 0 && line.text.isNotBlank()) line.copy(words =
                    listOf(LyricWord(line.timeMs, line.timeMs + 1000L, line.text))) else line
            }
        }
    }
    val motion = remember(lines) { LyricPlaybackStepMotion(0) }
    val layer = rememberGraphicsLayer()
    val draws = remember(lines, typography) { Channel<Int>(Channel.CONFLATED) }
    var step by remember(lines, typography) { mutableIntStateOf(0) }
    val phases = remember { floatArrayOf(1f, 0f, .5f, 1f) }
    val progress = remember(lines) { { lines.first().timeMs + 500L } }
    SideEffect { readiness.begin() }
    LaunchedEffect(lines, typography) {
        for (index in phases.indices) {
            step = index
            while (draws.receive() != index) { }
            // 绘制回调只表示命令已录制，至少跨过提交帧后再切换下一种图层状态。
            repeat(2) { withFrameNanos { } }
        }
        readiness.complete()
    }
    Column(Modifier.fillMaxSize().clearAndSetSemantics { }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        draws.trySend(step)
    }) {
        lines.forEachIndexed { index, line ->
            ImmersiveLyricLine(line, index, 0, typography, active = false,
                animateEmphasis = false, settleProgress = { phases[step] },
                playbackMotion = motion, smoothProgressMs = progress, onClick = {})
        }
    }
}

// 无本地歌曲时只练习同一套渲染路径，不写播放状态，不触发在线歌词请求。
private val lyricWarmupSamples = listOf(
    TimedLyric(0L, "音乐让每一刻自然流动", "Let the music flow",
        listOf(LyricWord(0L, 1000L, "音乐"), LyricWord(1000L, 2000L, "让每一刻自然流动"))),
    TimedLyric(2000L, "Keep the rhythm moving", "让旋律继续"),
)
