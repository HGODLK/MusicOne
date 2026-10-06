package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberQqFeedTextPreparer(width: Dp, height: Dp, widthPx: Int): QqFeedTextPreparer {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val style = MaterialTheme.typography.bodyMedium
    return remember(width, height, widthPx, density, direction, resolver, style) {
        val contentMax = qqMusicFeedContentMaxWidth(width, height)
        val gutter = if (width >= 600.dp) maxOf(32.dp, (width - contentMax) / 2) else 20.dp
        val columns = qqMusicFeedColumns(width, height)
        val widths = with(density) {
            qqFeedCardPixelWidths(widthPx, gutter.roundToPx(), 14.dp.roundToPx(), columns)
        }
        QqFeedTextPreparer(widths, density, direction, resolver, style)
    }
}

/** 复用固定列瀑布流的整数像素分配，覆盖列宽相差一个像素的情况。 */
internal fun qqFeedCardPixelWidths(width: Int, gutter: Int, spacing: Int, columns: Int): List<Int> {
    val available = (width - gutter * 2 - spacing * (columns - 1)).coerceAtLeast(columns)
    val base = available / columns
    return if (available % columns == 0) listOf(base) else listOf(base, base + 1)
}

internal class QqFeedTextPreparer(
    private val cardWidths: List<Int>,
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
    private val style: TextStyle,
) {
    suspend fun prepare(cards: List<QqMusicFeedCard>) = withContext(Dispatchers.Default) {
        // 每个后台任务独占测量器，不与 Compose 的 TextMeasurer 共享内部布局缓存。
        val measurer = TextMeasurer(resolver, density, direction, cacheSize = 0)
        val songInset = with(density) { 14.dp.roundToPx() * 2 }
        val playlistInset = with(density) { 14.dp.roundToPx() + 58.dp.roundToPx() }
        for (card in cards) {
            ensureActive()
            for (cardWidth in cardWidths) {
                when (card) {
                    is QqMusicFeedCard.Song -> {
                        val width = (cardWidth - songInset).coerceAtLeast(1)
                        prepareText(card.track.title, width, true, 1, measurer)
                        prepareText(card.track.artists, width, false, 1, measurer)
                        if (card.recommendationTitle.isNotBlank()) {
                            prepareText(card.recommendationTitle, width, false, 2, measurer)
                        }
                    }
                    is QqMusicFeedCard.Playlist -> {
                        val width = (cardWidth - playlistInset).coerceAtLeast(1)
                        prepareText(card.playlist.title, width, true, 3, measurer)
                        if (card.playlist.subtitle.isNotBlank()) {
                            prepareText(card.playlist.subtitle, width, false, 1, measurer)
                        }
                    }
                    // 三行货架和歌曲组使用固定字号，不需要预先试排。
                    is QqMusicFeedCard.SongGroup, is QqMusicFeedCard.SongShelf -> Unit
                }
            }
        }
    }

    private fun prepareText(text: String, width: Int, emphasis: Boolean, lines: Int, measurer: TextMeasurer) {
        QqFeedTextLayoutCache.fontSize(
            musicOneUiAnnotatedString(text), width,
            style.copy(fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal),
            density, direction, resolver, lines,
            maximum = if (emphasis) 16 else 14,
            minimum = if (emphasis) 13 else 11,
            measurer = measurer,
        )
    }
}
