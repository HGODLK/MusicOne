package com.musicone.demo

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
internal fun rememberPlaylistCardReorder(grid: LazyGridState, playlists: List<MusicPlaylist>, namespace: String,
    local: Boolean): PlaylistCardReorderState {
    val context = LocalContext.current.applicationContext
    val library = LocalMusicLibraryUi.current.store
    val preferences = remember(context) { PlaylistCardOrderPreferences(context) }
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val state = remember(grid, namespace, local, edge) {
        val ids = playlists.map { it.id }
        PlaylistCardReorderState(grid, scope,
            if (local) ids else preferences.read(namespace), ids, local, edge,
            save = { if (local) library.reorderPlaylists(it) else preferences.write(namespace, it) },
            onFailure = { Toast.makeText(context, it.message ?: "保存排序失败，已恢复原顺序", Toast.LENGTH_LONG).show() })
    }
    SideEffect { state.sync(playlists.map { it.id }) }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

internal fun PlaylistCardReorderState.arrange(playlists: List<MusicPlaylist>): List<MusicPlaylist> {
    val byId = playlists.associateBy { it.id }
    return reconcilePlaylistCardOrder(order, playlists.map { it.id }).mapNotNull(byId::get)
}

@Composable
internal fun Modifier.playlistCardLift(state: PlaylistCardReorderState, id: String): Modifier {
    val scale by animateFloatAsState(if (state.activeId == id && state.dragging && state.moved) 1.035f else 1f,
        musicSpring(), label = "歌单拖动抬起")
    return zIndex(if (state.activeId == id) 2f else 0f).graphicsLayer {
        val offset = state.translation(id)
        translationX = offset.x; translationY = offset.y
        scaleX = scale; scaleY = scale
        shadowElevation = 12.dp.toPx() * ((scale - 1f) / .035f).coerceIn(0f, 1f)
        shape = RoundedCornerShape(20.dp)
    }
}

@Composable
internal fun Modifier.playlistCardDrag(state: PlaylistCardReorderState?, id: String, enabled: Boolean,
    onStationary: (() -> Unit)?): Modifier {
    if (state == null || !enabled) return this
    val haptic = LocalHapticFeedback.current
    val menu by rememberUpdatedState(onStationary)
    return semantics {
        customActions = buildList {
            add(CustomAccessibilityAction("向前移动歌单") { state.moveBy(id, -1) })
            add(CustomAccessibilityAction("向后移动歌单") { state.moveBy(id, 1) })
            if (menu != null) add(CustomAccessibilityAction("管理歌单") { menu?.invoke(); true })
        }
    }.pointerInput(state, id, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!state.begin(id)) return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            var finished = false
            try {
                // 长按确认后在 Initial 阶段持有整段事件，静止抬手也不能落入卡片点击。
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                    if (change.isConsumed) break
                    val delta = change.positionChangeIgnoreConsumed()
                    change.consume()
                    if (change.changedToUpIgnoreConsumed()) {
                        finished = true
                        state.finish { menu?.invoke() }
                        break
                    }
                    state.move(delta)
                }
            } finally {
                if (!finished) state.cancel()
            }
        }
    }
}
