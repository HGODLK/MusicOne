package com.musicone.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistCollectionButton(saved: Boolean, updating: Boolean, onClick: () -> Unit) {
    val vertical by animateFloatAsState(if (saved) 0f else 1f, musicMotion(220), label = "歌单收藏符号")
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(onClick, enabled = !updating, modifier = Modifier.size(48.dp).semantics {
        contentDescription = if (updating) "正在同步歌单收藏" else if (saved) "取消收藏歌单" else "收藏歌单"
    }) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(size.width * .17f, center.y), Offset(size.width * .83f, center.y), stroke, StrokeCap.Round)
            if (vertical > 0f) drawLine(color.copy(alpha = vertical),
                Offset(center.x, center.y - size.height * .33f * vertical),
                Offset(center.x, center.y + size.height * .33f * vertical), stroke, StrokeCap.Round)
        }
    }
}
