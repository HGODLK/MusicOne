package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class AiGlassDialogEntry(val maximumWidth: Dp, val dismiss: () -> Unit, val content: @Composable () -> Unit)
private class AiGlassMenuEntry(val anchor: Rect, val title: String, val dismiss: () -> Unit, val rows: @Composable () -> Unit)

private class AiGlassOverlayState {
    var dialog by mutableStateOf<AiGlassDialogEntry?>(null)
    var menu by mutableStateOf<AiGlassMenuEntry?>(null)
}

private val LocalAiGlassOverlay = staticCompositionLocalOf<AiGlassOverlayState?> { null }

/** 仅弹层显示期间录制当前完整页面，聊天和歌单详情共用同一坐标系，弹层自身不进入采样。 */
@Composable
internal fun AiGlassOverlayHost(content: @Composable () -> Unit) {
    val state = remember { AiGlassOverlayState() }
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var retainedDialog by remember { mutableStateOf<AiGlassDialogEntry?>(null) }
    var retainedMenu by remember { mutableStateOf<AiGlassMenuEntry?>(null) }
    val dialogTransition = updateTransition(state.dialog != null, label = "AI 玻璃弹层")
    val menuTransition = updateTransition(state.menu != null, label = "AI 玻璃菜单")
    val recording = dialogTransition.currentState || dialogTransition.targetState ||
        menuTransition.currentState || menuTransition.targetState
    SideEffect {
        state.dialog?.let { retainedDialog = it }
        state.menu?.let { retainedMenu = it }
    }
    val menuEntry = state.menu ?: retainedMenu
    val menuOrigin = if (bounds.width > 0f && menuEntry != null) qualityMenuTransformOrigin(menuEntry.anchor.center, bounds)
        else TransformOrigin.Center
    CompositionLocalProvider(LocalAiGlassOverlay provides state) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, recording, captureContinuously = true)) {
                content()
            }
            // 锚定菜单只贴按钮出现，不加整页压暗；确认弹窗仍居中并轻微压暗。
            menuTransition.AnimatedVisibility(visible = { it },
                enter = fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .84f, transformOrigin = menuOrigin),
                exit = fadeOut(musicMotion(180)) + scaleOut(musicMotion(220), targetScale = .92f, transformOrigin = menuOrigin)) {
                AiGlassMenuWindow(backdrop, bounds, menuEntry) { state.menu?.dismiss?.invoke() }
            }
            dialogTransition.AnimatedVisibility(visible = { it },
                enter = fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .92f),
                exit = fadeOut(musicMotion(180)) + scaleOut(musicMotion(220), targetScale = .96f)) {
                val entry = retainedDialog
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .14f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { state.dialog?.dismiss?.invoke() }
                    .safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalMiniPlayerGlassSampling provides rememberMiniPlayerGlassSampling(backdrop)) {
                        MusicOneBackdropGlass(backdrop, bounds,
                            Modifier.widthIn(max = entry?.maximumWidth ?: 560.dp).fillMaxWidth().semantics { paneTitle = "AI 操作" }
                                .clickable(remember { MutableInteractionSource() }, indication = null) {},
                            RoundedCornerShape(28.dp), blurRadius = 28.dp,
                            containerColor = Color.Black.copy(alpha = .08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) {
                            entry?.content?.invoke()
                        }
                    }
                }
            }
        }
    }
    if (state.dialog != null) BackHandler { state.dialog?.dismiss?.invoke() }
    if (state.menu != null) BackHandler { state.menu?.dismiss?.invoke() }
}

/** 菜单沿用歌曲菜单的玻璃、行样式和进出场，按触发按钮实际矩形定位。 */
@Composable
private fun AiGlassMenuWindow(backdrop: GraphicsLayer, bounds: Rect, entry: AiGlassMenuEntry?, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        entry?.let { menu ->
            val anchor = menu.anchor.translate(-bounds.left, -bounds.top)
            val gap = with(density) { 8.dp.toPx() }
            val margin = with(density) { 12.dp.toPx() }
            Layout(content = {
                MusicOneBackdropGlass(backdrop, bounds, Modifier.fillMaxWidth(), RoundedCornerShape(28.dp),
                    blurRadius = 24.dp,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                    fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                        Text(menu.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp))
                        menu.rows()
                    }
                }
            }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                val safeWidth = (constraints.maxWidth - margin * 2f).coerceAtLeast(1f).toInt()
                val menuWidth = 360.dp.roundToPx().coerceAtMost(safeWidth)
                val maxHeight = (constraints.maxHeight - margin * 2f).coerceAtLeast(1f).toInt()
                val placeable = measurables.single().measure(
                    constraints.copy(minWidth = menuWidth, maxWidth = menuWidth, minHeight = 0, maxHeight = maxHeight))
                val offset = aiGlassMenuOffset(anchor,
                    Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                    IntSize(placeable.width, placeable.height), gap, margin)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(offset.x.toInt(), offset.y.toInt())
                }
            }
        }
    }
}

/** 用同页玻璃承载原确认内容与回调，保留业务层的执行和取消资格。 */
@Composable
internal fun AiGlassDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, maximumWidth: Dp = 560.dp) {
    val host = checkNotNull(LocalAiGlassOverlay.current)
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val currentContent = rememberUpdatedState<@Composable () -> Unit> {
        BoxWithConstraints {
            Column(Modifier.heightIn(max = maxHeight).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                Box(Modifier.weight(1f, fill = false)) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
    DisposableEffect(host) {
        val entry = AiGlassDialogEntry(maximumWidth, { currentDismiss.value() }, { currentContent.value() })
        host.dialog = entry
        onDispose { if (host.dialog === entry) host.dialog = null }
    }
}

/** 锚定到触发按钮的玻璃菜单，标题与行内容由调用方提供，关闭仍走原来的状态回调。 */
@Composable
internal fun AiGlassMenu(anchor: Rect, title: String, onDismissRequest: () -> Unit, rows: @Composable () -> Unit) {
    val host = checkNotNull(LocalAiGlassOverlay.current)
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val currentRows = rememberUpdatedState(rows)
    DisposableEffect(host) {
        val entry = AiGlassMenuEntry(anchor, title, { currentDismiss.value() }, { currentRows.value() })
        host.menu = entry
        onDispose { if (host.menu === entry) host.menu = null }
    }
}
