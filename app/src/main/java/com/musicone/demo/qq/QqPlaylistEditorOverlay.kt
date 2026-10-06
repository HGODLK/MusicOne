package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*

/** 卡片保留原位置并抬起，侧翼与输入区共享同一块玻璃表面。 */
@Composable
internal fun QqPlaylistEditorOverlay(editor: QqPlaylistEditorState, host: Rect, backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer, bottomInset: Dp,
    onChanged: (PlaylistEditMode, MusicPlaylist?) -> Unit, onRestored: () -> Unit, onClosed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var retained by remember { mutableStateOf(false) }
    LaunchedEffect(editor.open) {
        if (editor.open) retained = true
        progress.animateTo(if (editor.open) 1f else 0f, musicMotion(320))
        if (!editor.open) {
            onRestored()
            withFrameNanos { }
            withFrameNanos { }
            retained = false
            onClosed()
        }
    }
    if (!editor.open && !retained) return
    BackHandler { editor.back() }
    val density = LocalDensity.current
    val backdropBounds = host
    val target = editor.playlist
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
            .background(Color.Black.copy(alpha = .12f))
            .clickable(remember { MutableInteractionSource() }, null) { editor.back() })
        val card = editor.anchor.translate(-host.left, -host.top)
        val cardSize = with(density) { card.width.toDp() }
        val right = card.center.x < host.width / 2
        val wingWidth = 108.dp
        val x = with(density) { card.left.toDp() }
        val y = with(density) { card.top.toDp() }
        val displayedMode = if (!editor.open && target != null) PlaylistEditMode.Actions else editor.mode
        val actions = displayedMode == PlaylistEditMode.Actions
        val form by androidx.compose.animation.core.animateFloatAsState(if (actions) 0f else 1f, musicMotion(280), label = "歌单表单交接")
        val sideWidth = (cardSize + wingWidth * progress.value).coerceAtMost(maxWidth - 24.dp)
        val formWidth = minOf(360.dp, maxWidth - 32.dp)
        val sideX = (x - if (right) 0.dp else wingWidth * progress.value)
            .coerceIn(12.dp, (maxWidth - sideWidth - 12.dp).coerceAtLeast(12.dp))
        val formX = x.coerceIn(12.dp, (maxWidth - formWidth - 12.dp).coerceAtLeast(12.dp))
        val panelWidth = sideWidth + (formWidth - sideWidth) * form
        val panelX = sideX + (formX - sideX) * form
        val panelY = (if (target == null) maxHeight * .25f else y)
            .coerceIn(80.dp, (maxHeight - bottomInset - 240.dp).coerceAtLeast(80.dp))
        val animatedY by androidx.compose.animation.core.animateDpAsState(panelY, musicMotion(300), label = "编辑窗口位置")
        MusicOneBackdropGlass(backdrop, backdropBounds,
            Modifier.offset(panelX, animatedY).width(panelWidth)
                .then(if (target != null) Modifier.height(cardSize) else Modifier)
                .graphicsLayer {
                    alpha = progress.value
                }, RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .44f)) {
            AnimatedContent(displayedMode, transitionSpec = {
                (fadeIn(musicMotion(240)) + slideInHorizontally(musicMotion(300)) { it / 8 }) togetherWith
                    (fadeOut(musicMotion(160)) + slideOutHorizontally(musicMotion(220)) { -it / 8 })
            }, label = "卡片菜单展开输入框") { mode ->
                if (mode == PlaylistEditMode.Actions && target != null) {
                    Row(Modifier.fillMaxWidth().height(cardSize), verticalAlignment = Alignment.CenterVertically) {
                        if (right) Spacer(Modifier.weight(1f))
                        Column(Modifier.width(wingWidth).graphicsLayer {
                            translationX = (if (right) -1 else 1) * wingWidth.toPx() * (1f - progress.value)
                            alpha = progress.value
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { editor.mode = PlaylistEditMode.Rename }, Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Edit, "重命名歌单")
                            }
                            IconButton(onClick = { editor.mode = PlaylistEditMode.Delete }, Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "删除歌单", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (!right) Spacer(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight - animatedY - 16.dp).coerceAtLeast(120.dp))
                        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(when (mode) { PlaylistEditMode.Create -> "新建歌单"; PlaylistEditMode.Delete -> "删除歌单？"; else -> "重命名歌单" },
                            style = MaterialTheme.typography.titleLarge)
                        if (mode == PlaylistEditMode.Delete) Text("将从 QQ 音乐删除「${target?.title}」，此操作无法撤销。", style = MaterialTheme.typography.bodyMedium)
                        else OutlinedTextField(editor.name, { editor.name = it }, enabled = !editor.busy,
                            singleLine = true, label = { Text("歌单名称") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                        editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = editor::back, enabled = !editor.busy) { Text("取消") }
                            TextButton(onClick = { editor.submit(onChanged) }, enabled = !editor.busy &&
                                (mode == PlaylistEditMode.Delete || editor.name.isNotBlank())) {
                                Text(if (editor.busy) "正在处理…" else if (mode == PlaylistEditMode.Delete) "确认删除" else "保存")
                            }
                        }
                    }
                }
            }
        }
        if (target != null) {
            val lifted = 1f - form
            CompositionLocalProvider(LocalPlaylistMotion provides null, LocalPlaylistCardTransition provides null) {
            QqPlaylistCard(target, {}, {}, Modifier.offset(x, y + (panelY - y) * progress.value).width(cardSize).graphicsLayer {
                scaleX = 1f + .035f * progress.value; scaleY = scaleX
                alpha = lifted; shadowElevation = 16.dp.toPx() * progress.value
                shape = RoundedCornerShape(20.dp); clip = true
            }, width = cardSize, sharedTransition = false, artworkVersion = editor.artworkVersion)
            }
        }
    }
}
