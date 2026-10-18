package com.musicone.demo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

internal val LocalPlayerResourcePreparationAllowed = staticCompositionLocalOf { false }

/** 性能提示和资源预备共享动作状态，但都不修改动画、列表或绘制合成状态。 */
@Composable
internal fun PlayerInteractionPerformance(motion: PageMotion, lyrics: PhoneLyricsMotion, track: MusicTrack,
    lyricsRequested: Boolean, blocked: Boolean) {
    if (!BuildConfig.MUSICONE_ADPF_HINTS && !BuildConfig.MUSICONE_LYRIC_RESOURCE_PREPARE) return
    val view = LocalView.current
    val visible = LocalPlayerVisible.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val resourcesAllowed = LocalPlayerResourcePreparationAllowed.current
    val navigation = LocalEntityNavigation.current
    val songMenu = LocalQqPlaylistSongMenu.current
    val qualityMenu = LocalPlayerQualityMenu.current
    val hints = remember(view) {
        if (BuildConfig.MUSICONE_ADPF_HINTS && Build.VERSION.SDK_INT >= 31)
            view.context.performanceWindow()?.let(::WindowInteractionHints) else null
    }
    DisposableEffect(hints) { onDispose { if (Build.VERSION.SDK_INT >= 31) hints?.close() } }
    LaunchedEffect(motion, lyrics, hints, visible, foreground, resourcesAllowed, track.source, track.id, track.lyrics, lyricsRequested, blocked) {
        try {
            snapshotFlow {
                val available = visible && foreground
                val busy = playerInteractionBusy(motion.phase, lyrics.dragging, lyrics.lyricsProgress, lyrics.headerProgress, lyricsRequested)
                val covered = blocked || navigation?.pages?.isNotEmpty() == true ||
                    songMenu?.expanded == true || qualityMenu?.expanded == true
                val hinting = available && lyricInteractionHintsAllowed(motion.phase, motion.wantsOpen, covered,
                    lyrics.dragging, lyrics.lyricsProgress, lyrics.headerProgress, lyricsRequested)
                Triple(available && !covered, busy, hinting)
            }.collectLatest { (available, busy, hinting) ->
                if (Build.VERSION.SDK_INT >= 31) hints?.setActive(hinting)
                if (available && !busy && motion.phase == MotionPhase.SHOWN && resourcesAllowed &&
                    BuildConfig.MUSICONE_LYRIC_RESOURCE_PREPARE) {
                    // 等正常画面提交后再准备已有缓存，动作开始会取消本轮剩余工作。
                    repeat(2) { withFrameNanos { } }
                    withContext(Dispatchers.Default) { prepareCachedLyricRasters() }
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 31) hints?.setActive(false)
        }
    }
}

/** 歌词动作独占实验范围；整页转场和其他界面接管时立即退出。 */
internal fun lyricInteractionHintsAllowed(phase: MotionPhase, wantsOpen: Boolean, blocked: Boolean,
    dragging: Boolean, lyrics: Float, header: Float, requested: Boolean): Boolean =
    phase == MotionPhase.SHOWN && wantsOpen && !blocked &&
        playerInteractionBusy(MotionPhase.SHOWN, dragging, lyrics, header, requested)

internal fun playerInteractionBusy(phase: MotionPhase, dragging: Boolean, lyrics: Float, header: Float,
    requested: Boolean? = null): Boolean {
    val target = if (requested == true) 1f else 0f
    val pending = requested != null && (lyrics != target || header != target)
    return phase == MotionPhase.PREPARING || phase == MotionPhase.MOVING || phase == MotionPhase.DRAGGING ||
        dragging || pending || lyrics != 0f && lyrics != 1f || header != 0f && header != 1f
}

private fun Context.performanceWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> if (baseContext === this) null else baseContext.performanceWindow()
    else -> null
}
