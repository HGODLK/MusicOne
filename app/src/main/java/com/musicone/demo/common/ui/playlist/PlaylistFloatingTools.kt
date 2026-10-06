package com.musicone.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistFloatingTools(
    state: PlaylistSearchState,
    canLocate: Boolean,
    onLocate: () -> Unit,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    bottomInset: Dp,
    motionProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val locateAlpha = animateFloatAsState(if (canLocate) 1f else .38f, musicMotion(220), label = "定位可用状态")
    val searchExpansion = remember { Animatable(if (state.expanded) 1f else 0f) }
    val fieldWidth = motionLerp(52f, 236f, searchExpansion.value).dp
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val edgePadding = 20.dp
    val edgePaddingPx = with(density) { edgePadding.toPx() }
    val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val resolvedBottomPadding = animateDpAsState(
        playlistFloatingToolsBottomPadding(bottomInset, imeBottomInset, edgePadding),
        musicMotion(220),
        label = "歌单搜索工具底部位置",
    ).value
    LaunchedEffect(state.expanded) {
        if (state.expanded) {
            searchExpansion.animateTo(1f, musicMotion(260))
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        } else {
            keyboard?.hide()
            searchExpansion.animateTo(0f, musicMotion(220))
        }
    }

    Column(
        modifier = modifier
            .padding(end = edgePadding, bottom = resolvedBottomPadding)
            .graphicsLayer {
                val progress = motionProgress()
                alpha = progress.coerceIn(0f, 1f)
                translationX = playlistFloatingToolsOffset(progress, size.width, edgePaddingPx)
            },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = !state.expanded,
            enter = fadeIn(musicMotion(180)) + expandVertically(musicMotion(220), expandFrom = Alignment.Bottom),
            exit = fadeOut(musicMotion(140)) + shrinkVertically(musicMotion(180), shrinkTowards = Alignment.Bottom),
        ) {
            MusicOneBackdropGlass(
                backdropLayer = backdropLayer,
                backdropBounds = backdropBounds,
                modifier = Modifier.size(52.dp).graphicsLayer { alpha = locateAlpha.value },
                shape = CircleShape,
            ) {
                IconButton(onClick = onLocate, enabled = canLocate, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = "定位当前播放歌曲", modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        MusicOneBackdropGlass(
            backdropLayer = backdropLayer,
            backdropBounds = backdropBounds,
            modifier = Modifier.width(fieldWidth).height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 搜索图标始终位于展开容器左端，宽度变化时自然连续移动，避免切换两套内容造成跳变。
                IconButton(
                    onClick = state::open,
                    enabled = !state.expanded,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = if (state.expanded) null else "搜索歌单歌曲",
                        modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.expanded || searchExpansion.value > 0f) {
                    Row(
                        Modifier.fillMaxSize().padding(start = 52.dp).graphicsLayer {
                            alpha = playlistSearchContentAlpha(searchExpansion.value)
                            translationX = (1f - searchExpansion.value) * 8.dp.toPx()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = state.query,
                            onValueChange = { state.query = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).focusRequester(focusRequester),
                            enabled = state.expanded,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        )
                        IconButton(onClick = state::close, enabled = state.expanded, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索", modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}

internal fun playlistFloatingToolsOffset(progress: Float, width: Float, edgePadding: Float): Float =
    (width.coerceAtLeast(0f) + edgePadding.coerceAtLeast(0f)) * (1f - progress.coerceIn(0f, 1f))

internal fun playlistFloatingToolsBottomPadding(bottomInset: Dp, imeInset: Dp, edgePadding: Dp): Dp =
    maxOf(bottomInset, imeInset) + edgePadding

internal fun playlistSearchContentAlpha(progress: Float): Float =
    ((progress.coerceIn(0f, 1f) - .18f) / .82f).coerceIn(0f, 1f)
