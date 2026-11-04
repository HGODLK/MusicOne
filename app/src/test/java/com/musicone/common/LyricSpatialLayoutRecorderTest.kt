package com.musicone

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LyricSpatialLayoutRecorderTest {
    @Test fun cachedTypographyStillUsesNewWidthsRowsAndDensityInEveryLayoutMode() {
        for (mode in listOf(PlayerLayoutMode.COMPACT_SINGLE, PlayerLayoutMode.WIDE_SINGLE)) {
            for (scale in listOf(1f, 2.75f)) {
                val density = Density(scale, 1.6f)
                val travel = PhoneLyricsSpatialTravel()
                val recorder = LyricSpatialLayoutRecorder(travel, density, mode)
                for (width in listOf(340, 700, 340)) for (height in listOf(49, 53, 73, 190)) {
                    val rows = listOf(row(-1, -200, 30), row(0, -10, height),
                        row(1, height + 8, height, translated = true))
                    val layout = layout((width * scale).toInt(), rows)
                    val protection = LyricBlurProtection(height + 30f, true)
                    val reference = PhoneLyricsSpatialTravel().apply {
                        record(layout, density, protection,
                            lyricTypographyForWidth(mode, layout.viewportSize.width / scale))
                        capture()
                    }
                    recorder.record(layout, protection)
                    travel.capture()
                    assertEquals(reference.compactHeightPx, travel.compactHeightPx, 0f)
                    assertEquals(reference.softnessPx, travel.softnessPx, 0f)
                    assertEquals(protection, travel.blurProtection)
                }
            }
        }
    }

    @Test fun sameLayoutStillPublishesMovingFocusAndHandoffDoesNotChangeCapturedTravel() {
        val travel = PhoneLyricsSpatialTravel()
        val recorder = LyricSpatialLayoutRecorder(travel, Density(1f), PlayerLayoutMode.COMPACT_SINGLE)
        val sameLayout = layout(340, listOf(row(0, 0, 49), row(1, 67, 49)))
        recorder.record(sameLayout, LyricBlurProtection(70f, true))
        travel.capture()
        val height = travel.compactHeightPx
        recorder.record(sameLayout, LyricBlurProtection(85f, true))
        assertEquals(85f, travel.blurProtection.focusBottom!!, 0f)
        recorder.record(layout(700, listOf(row(0, 0, 260))), LyricBlurProtection.HANDOFF)
        travel.capture()
        assertEquals(height, travel.compactHeightPx, 0f)
        assertEquals(LyricBlurProtection.HANDOFF, travel.blurProtection)
        recorder.record(sameLayout, LyricBlurProtection(60f, true))
        assertEquals(60f, travel.blurProtection.focusBottom!!, 0f)
    }

    @Test fun twoVisibleRowsDoNotRequireReadingEveryFollowingParagraph() {
        val unread = proxy<LazyListItemInfo>(emptyMap())
        val travel = PhoneLyricsSpatialTravel()
        travel.record(layout(340, listOf(row(0, -200, 30), row(1, -10, 49),
            row(2, 57, 49), unread)), Density(1f), LyricBlurProtection(null, true))
        travel.capture()
        assertEquals(116f, travel.compactHeightPx, 0f)
    }

    private fun row(index: Int, offset: Int, size: Int, translated: Boolean = false): LazyListItemInfo =
        proxy(mapOf("getIndex" to index, "getOffset" to offset, "getSize" to size, "getContentType" to translated))

    private fun layout(width: Int, rows: List<LazyListItemInfo>): LazyListLayoutInfo = proxy(mapOf(
        "getVisibleItemsInfo" to rows, "getViewportStartOffset" to 0, "getViewportEndOffset" to 900,
        "getViewportSize-YbymL2g" to ((width.toLong() shl 32) or 900L), "getMainAxisItemSpacing" to 18))

    private inline fun <reified T> proxy(values: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: error("未预期的布局读取：${method.name}")
        } as T
}
