package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun miniPlayerAccessoryAlpha(progress: Float): Float {
    val remaining = 1f - (progress / MINI_PLAYER_ACCESSORY_FADE_END).coerceIn(0f, 1f)
    return remaining * remaining * (3f - 2f * remaining)
}

private const val MINI_PLAYER_ACCESSORY_FADE_END = .6f

@Composable
internal fun FlyingMiniPlayerAccessories(motion: PageMotion, favorite: Boolean) {
    if (!motion.moving) return
    val handoff = LocalPlayerControlHandoff.current
    Box(Modifier.fillMaxSize()) {
        FlyingMiniPlayerAccessory(
            motion = motion,
            key = "miniFavorite",
            icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            tint = if (favorite) PlayerFavoriteColor else handoff?.sourceInk(
                "favorite",
                MaterialTheme.colorScheme.onSurfaceVariant,
            ) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            iconSize = 18,
        )
        FlyingMiniPlayerAccessory(
            motion = motion,
            key = "miniNext",
            icon = Icons.Default.SkipNext,
            tint = handoff?.sourceInk("next", MaterialTheme.colorScheme.onSurface)
                ?: MaterialTheme.colorScheme.onSurface,
            iconSize = 21,
        )
    }
}

@Composable
private fun FlyingMiniPlayerAccessory(
    motion: PageMotion,
    key: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    iconSize: Int,
) {
    val bounds = motion.sourceSnapshot[key] ?: return
    val density = LocalDensity.current
    val localBounds = Rect(
        left = bounds.bounds.left - motion.hostBounds.left,
        top = bounds.bounds.top - motion.hostBounds.top,
        right = bounds.bounds.right - motion.hostBounds.left,
        bottom = bounds.bounds.bottom - motion.hostBounds.top,
    )
    Box(
        Modifier
            .offset { IntOffset(localBounds.left.roundToInt(), localBounds.top.roundToInt()) }
            .size(with(density) { localBounds.width.toDp() }, with(density) { localBounds.height.toDp() })
            .graphicsLayer { alpha = miniPlayerAccessoryAlpha(motion.value) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}
