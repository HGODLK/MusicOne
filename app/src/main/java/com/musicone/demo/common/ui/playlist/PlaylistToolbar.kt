package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistToolbar(
    layer: GraphicsLayer,
    bounds: Rect,
    onBack: () -> Unit,
    showCollection: Boolean = false,
    saved: Boolean = false,
    updating: Boolean = false,
    onToggleSaved: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val motion = LocalPlaylistMotion.current
    val frame = LocalEntityFrame.current
    SideEffect { frame?.backAction = onBack }
    val slideDistance = with(LocalDensity.current) { 76.dp.toPx() }
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (LocalUnifiedBack.current) Spacer(Modifier.size(48.dp)) else MusicOneBackdropGlass(layer, bounds,
            shape = CircleShape,
            visualOffset = {
                Offset(playlistBackButtonOffset(motion?.value ?: 1f, slideDistance), 0f)
            }) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
            }
        }
        Spacer(Modifier.weight(1f))
        if (showCollection) MusicOneBackdropGlass(layer, bounds,
            shape = CircleShape,
            visualOffset = {
                Offset(playlistForwardButtonOffset(motion?.value ?: 1f, slideDistance), 0f)
            }) {
            PlaylistCollectionButton(saved, updating, onToggleSaved)
        }
    }
}

internal fun playlistBackButtonOffset(progress: Float, distance: Float): Float =
    distance * (progress.coerceIn(0f, 1f) - 1f)

internal fun playlistForwardButtonOffset(progress: Float, distance: Float): Float =
    distance * (1f - progress.coerceIn(0f, 1f))
