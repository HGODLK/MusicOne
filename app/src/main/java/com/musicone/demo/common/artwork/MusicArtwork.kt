package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Artwork(start: Long, end: Long, mark: String, modifier: Modifier, markSize: TextUnit, markAlignment: Alignment = Alignment.TopEnd, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)) {
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(start), Color(end))))) {
        Text(mark, color = Color.White, fontSize = markSize, fontWeight = FontWeight.Black,
            lineHeight = if (markAlignment == Alignment.Center) markSize else TextUnit.Unspecified,
            maxLines = if (markAlignment == Alignment.Center) 1 else Int.MAX_VALUE,
            modifier = Modifier.align(markAlignment).then(
                if (markAlignment == Alignment.Center) Modifier else Modifier.padding(horizontal = 11.dp, vertical = 8.dp)))
    }
}

@Composable
internal fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFFF7F8FA)) {
        Box(Modifier.padding(30.dp), contentAlignment = Alignment.Center) { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
