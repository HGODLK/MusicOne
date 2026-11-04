package com.musicone

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** 界面线程转发现有宿主绘制，独立原生子层更新也会经过此处；不申请额外帧。 */
internal class MiniPlayerBackdropUpdates {
    private var revision = 0L
    private val subscribers = linkedMapOf<Any, (Long) -> Unit>()

    fun version(): Long = revision
    fun register(owner: Any, update: (Long) -> Unit) { subscribers[owner] = update }
    fun remove(owner: Any) { subscribers.remove(owner) }

    fun hostDraw() {
        revision++
        // 回调读取采样几何和显隐资格，不能反向让整页绘制订阅播放页的连续进度。
        Snapshot.withoutReadObservation {
            subscribers.values.forEach { it(revision) }
        }
    }
}

internal val LocalMiniPlayerBackdropUpdates = staticCompositionLocalOf<MiniPlayerBackdropUpdates?> { null }

@Composable
internal fun rememberMiniPlayerBackdropUpdates(): MiniPlayerBackdropUpdates {
    val updates = remember { MiniPlayerBackdropUpdates() }
    val view = LocalView.current
    DisposableEffect(view, updates) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener { updates.hostDraw() }
        observer.addOnDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnDrawListener(listener)
            else view.viewTreeObserver.removeOnDrawListener(listener)
        }
    }
    return updates
}

internal class MiniPlayerGlassSampling(val updates: MiniPlayerBackdropUpdates, val source: GraphicsLayer,
    val allowed: State<Boolean>?)

internal val LocalMiniPlayerGlassSampling = staticCompositionLocalOf<MiniPlayerGlassSampling?> { null }

internal data class MiniPlayerGlassSampleKey(val bounds: Rect, val sourceBounds: Rect, val size: IntSize,
    val density: Density, val direction: LayoutDirection, val radius: Float, val fallback: Color)

@Composable
internal fun rememberMiniPlayerGlassSampling(source: GraphicsLayer): MiniPlayerGlassSampling? {
    val updates = LocalMiniPlayerBackdropUpdates.current ?: return null
    val motion = LocalPlayerMotion.current
    val allowed = remember(motion) { motion?.let(::playerBackdropRefreshState) }
    return remember(updates, source, allowed) { MiniPlayerGlassSampling(updates, source, allowed) }
}

/** 配置只保存独立原生录制命令，不保留临时 DrawScope；同一宿主帧只录制一次。 */
internal class MiniPlayerGlassRecording {
    private var record: (() -> Unit)? = null
    private var configuration: Any? = null
    private var recordedVersion = Long.MIN_VALUE

    fun configure(key: Any, record: () -> Unit) {
        if (configuration != key) { configuration = key; recordedVersion = Long.MIN_VALUE }
        this.record = record
    }
    fun refresh(version: Long) {
        val recording = record ?: return
        if (recordedVersion == version) return
        recording()
        recordedVersion = version
    }
    fun clear() { record = null; configuration = null; recordedVersion = Long.MIN_VALUE }
}

@Composable
internal fun rememberMiniPlayerGlassRecording(sampling: MiniPlayerGlassSampling?, source: GraphicsLayer?,
    enabled: Boolean): MiniPlayerGlassRecording? {
    val binding = sampling?.takeIf { enabled && it.source === source }
    val recording = remember(binding, source) { if (binding != null) MiniPlayerGlassRecording() else null }
    DisposableEffect(binding, source, recording) {
        if (binding != null && source != null && recording != null) {
            binding.updates.register(recording) { version ->
                if (binding.allowed?.value != false && !ExperiencePreferences.options.disableBlur)
                    recording.refresh(version)
            }
        }
        onDispose {
            if (recording != null) { binding?.updates?.remove(recording); recording.clear() }
        }
    }
    return recording
}
