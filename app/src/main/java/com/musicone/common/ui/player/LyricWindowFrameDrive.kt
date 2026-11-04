package com.musicone

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/** 消费已经放行的歌词帧，同时保证局部图层更新提交到 Android 宿主。 */
@Composable
internal fun Modifier.lyricWindowFrameDrive(active: Boolean): Modifier {
    if (!BuildConfig.MUSICONE_LYRIC_FRAME_DRIVE && !BuildConfig.MUSICONE_TRACE) return this
    val driver = LocalLyricFrameDriver.current ?: return this
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val queue = LocalPlayerQueueState.current
    val enabled = active && LocalPlayerVisible.current && !LocalPlayerPrewarming.current &&
        LocalWindowInfo.current.isWindowFocused && state.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(driver, view, enabled) {
        val owner = Any()
        val probe = if (enabled && BuildConfig.MUSICONE_TRACE && Build.VERSION.SDK_INT >= 24)
            LyricWindowFrameProbe(view) { queue.visible } else null
        if (enabled) driver.register(owner) { time ->
            // 恢复原宿主提交保证；不新增时钟，也不重新组合或录制整页。
            if (BuildConfig.MUSICONE_LYRIC_FRAME_DRIVE) view.invalidate()
            probe?.onLyricFrame(time)
        }
        onDispose { driver.remove(owner); probe?.close() }
    }
    return then(LyricFrameDriveElement(driver, enabled && BuildConfig.MUSICONE_LYRIC_FRAME_DRIVE))
}

private data class LyricFrameDriveElement(val driver: LyricFrameDriver, val enabled: Boolean) :
    ModifierNodeElement<LyricFrameDriveNode>() {
    override fun create() = LyricFrameDriveNode(driver, enabled)
    override fun update(node: LyricFrameDriveNode) = node.update(driver, enabled)
    override fun InspectorInfo.inspectableProperties() { name = "lyricWindowFrameDrive" }
}

private class LyricFrameDriveNode(var driver: LyricFrameDriver, var enabled: Boolean) :
    Modifier.Node(), DrawModifierNode {
    override fun onAttach() { if (enabled) driver.register(this) { invalidateDraw() } }
    override fun onDetach() { driver.remove(this) }
    fun update(next: LyricFrameDriver, active: Boolean) {
        if (driver === next && enabled == active) return
        if (isAttached) onDetach()
        driver = next
        enabled = active
        if (isAttached) onAttach()
    }
    override fun ContentDrawScope.draw() { drawContent() }
}
