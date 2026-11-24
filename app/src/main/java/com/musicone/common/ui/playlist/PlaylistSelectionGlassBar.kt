package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 歌单卡片多选的全局出口：页面登记当前的多选操作条与待确认的批量操作，
 * 操作条由底栏绘制，确认框由音乐库宿主盖在整个应用上方，两者都用玻璃。
 */
@Stable
internal class PlaylistSelectionBarHost {
    var request by mutableStateOf<PlaylistSelectionBarRequest?>(null)
    var confirm by mutableStateOf<PlaylistBatchConfirm?>(null)
}

@Stable
internal class PlaylistSelectionBarRequest(val selection: PlaylistCardSelection) {
    var allIds by mutableStateOf(emptyList<String>())
    var actionLabel by mutableStateOf("")
    var onAction: () -> Unit = {}
}

internal val LocalPlaylistSelectionBarHost = staticCompositionLocalOf { PlaylistSelectionBarHost() }

/** 多选操作条：取消、已选数量或进度、整页全选和批量动作；玻璃与迷你播放条同一套底色、描边和圆角。 */
@Composable
internal fun PlaylistSelectionGlassBar(request: PlaylistSelectionBarRequest, backdropLayer: GraphicsLayer,
    backdropBounds: Rect, immersiveVisual: SourceBackdropVisual?, modifier: Modifier = Modifier) {
    val selection = request.selection
    val style = LocalBottomBarStyle.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val allIds = request.allIds
    MusicOneBackdropGlass(
        backdropLayer = backdropLayer,
        backdropBounds = backdropBounds,
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth(),
        shape = RoundedCornerShape(style.surfaceCorner),
        blurRadius = 18.dp,
        containerColor = immersiveVisual?.panelColor
            ?: if (dark) Color(0xFF242526).copy(alpha = .82f) else Color.White.copy(alpha = style.lightTint),
        fallbackColor = immersiveVisual?.panelColor ?: if (dark) Color(0xFF242526) else Color(0xFFF3F4F6),
        border = BorderStroke(1.dp, immersiveVisual?.panelBorder
            ?: if (dark) Color.White.copy(alpha = .16f) else Color(0xFFE0E2E6)),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionTextAction("取消", !selection.busy) { selection.close() }
            AnimatedContent(selection.busyText ?: "已选 ${selection.ids.size} 个", Modifier.weight(1f),
                transitionSpec = { fadeIn(musicMotion(200)) togetherWith fadeOut(musicMotion(140)) },
                contentAlignment = Alignment.Center, label = "多选数量") { text ->
                Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            SelectionTextAction(if (allIds.isNotEmpty() && selection.ids.containsAll(allIds)) "取消全选" else "全选",
                !selection.busy && allIds.isNotEmpty()) { selection.toggleAll(allIds) }
            SelectionTextAction(request.actionLabel, !selection.busy && selection.ids.isNotEmpty(),
                MaterialTheme.colorScheme.error) { request.onAction() }
        }
    }
}

/** 多选里的文字按钮；文字变化时淡入淡出，不直接跳变。 */
@Composable
internal fun SelectionTextAction(label: String, enabled: Boolean, color: Color = MaterialTheme.colorScheme.onSurface,
    action: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = action)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(label, transitionSpec = { fadeIn(musicMotion(200)) togetherWith fadeOut(musicMotion(140)) },
            contentAlignment = Alignment.Center, label = "多选操作文字") { text ->
            Text(text, color = color.copy(alpha = if (enabled) 1f else .38f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1)
        }
    }
}

/** 批量操作的确认框：沿用音乐库编辑弹窗的玻璃、缩放淡入与返回规则，盖住整个应用含底栏。 */
@Composable
internal fun PlaylistBatchConfirmOverlay(host: PlaylistSelectionBarHost, backdrop: GraphicsLayer, bounds: Rect) {
    val request = host.confirm
    var retained by remember { mutableStateOf<PlaylistBatchConfirm?>(null) }
    SideEffect { if (request != null) retained = request }
    AnimatedVisibility(request != null, enter = fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .84f),
        exit = fadeOut(musicMotion(180)) + scaleOut(musicMotion(220), targetScale = .92f)) {
        val shown = request ?: retained ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { host.confirm = null },
            contentAlignment = Alignment.Center) {
            MusicOneBackdropGlass(backdrop, bounds, Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) {}, RoundedCornerShape(28.dp),
                containerColor = playlistMenuContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(shown.title, style = MaterialTheme.typography.titleLarge)
                    Text(shown.detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { host.confirm = null }) { Text("取消") }
                        TextButton(onClick = { host.confirm = null; shown.run() }) {
                            Text(shown.confirmLabel, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (request != null) BackHandler { host.confirm = null }
}
