package com.musicone.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun FavoriteRemovalAction(
    pendingRemoval: Boolean,
    onMore: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        if (pendingRemoval) 1f else 0f,
        musicMotion(260),
        label = "更多操作与撤销收藏切换",
    )
    IconButton(onClick = { if (pendingRemoval) onUndo() else onMore() }, modifier = modifier.size(48.dp)) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer {
            alpha = 1f - progress
            scaleX = 1f - progress * .28f
            scaleY = scaleX
            rotationZ = progress * 24f
        }) {
            Icon(Icons.Default.MoreHoriz, "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RemovedFavoriteIcon(progress)
      }
    }
}

@Composable
private fun RemovedFavoriteIcon(progress: Float) {
    val red = Color(0xFFD32F2F)
    Box(modifier = Modifier.size(24.dp).graphicsLayer {
        alpha = progress
        scaleX = .72f + progress * .28f
        scaleY = scaleX
        rotationZ = -24f * (1f - progress)
    }) {
        Icon(Icons.Default.FavoriteBorder, "撤销取消收藏", tint = red,
            modifier = Modifier.size(24.dp).drawWithContent {
                drawContent()
                drawLine(red, Offset(size.width * .12f, size.height * .08f),
                    Offset(
                        size.width * (.12f + .76f * progress),
                        size.height * (.08f + .84f * progress),
                    ), 2.dp.toPx(), StrokeCap.Round)
            })
    }
}
