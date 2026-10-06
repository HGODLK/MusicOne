package com.musicone.demo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** 先在可读字号范围内适配行数，仍放不下时允许卡片长高，避免截断推荐文案。 */
@Composable
internal fun QqMusicFeedText(
    text: String,
    widthPx: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    emphasis: Boolean = false,
    preferredLines: Int = 2,
    maxLines: Int = Int.MAX_VALUE,
    compact: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val annotatedText = remember(text) { musicOneUiAnnotatedString(text) }
    val base = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
    )
    // 卡片层提供实际排版宽度，标题、歌手和推荐文案共用一次约束读取。
    val width = widthPx.coerceAtLeast(1)
    val maximum = if (emphasis) {
        if (compact) 16 else 18
    } else {
        if (compact) 14 else 15
    }
    val minimum = if (emphasis) {
        if (compact) 13 else 14
    } else {
        if (compact) 11 else 12
    }
    val fontSize = remember(annotatedText, width, base, density, direction, resolver, preferredLines, maximum, minimum) {
        QqFeedTextLayoutCache.fontSize(annotatedText, width, base, density, direction, resolver,
            preferredLines, maximum, minimum, measurer)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(annotatedText, Modifier.weight(1f, fill = false), maxLines = maxLines, color = color,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = base.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp))
        trailing()
    }
}
