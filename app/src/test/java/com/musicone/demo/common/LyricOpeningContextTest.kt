package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LyricOpeningContextTest {
    @Test fun closedWindowCanStandByButClosingAndReversingKeepTheirCoordinates() {
        assertTrue(phoneLyricsStandby(true, false, 0f, 0f, false))
        assertFalse(phoneLyricsStandby(false, false, 0f, 0f, false))
        assertFalse(phoneLyricsStandby(true, true, 0f, 0f, false))
        assertFalse(phoneLyricsStandby(true, false, .0001f, 0f, false))
        assertFalse(phoneLyricsStandby(true, false, 0f, .0001f, false))
        assertFalse(phoneLyricsStandby(true, false, 0f, 0f, true))
    }

    @Test fun preparedOpeningSurvivesLayoutSnapshotReplacementWithoutRepositioning() {
        val rows = (4..8).map { row(it, (it - 8) * 70, 52) }
        val original = layout(-268, rows)
        val context = LyricOpeningContext(5, original)
        val standby = LyricOpeningStandby()
        assertFalse(standby.matches(8, original, context))
        standby.record(8, original, context)
        assertTrue(standby.matches(8, layout(-268, rows), context))
        standby.invalidate()
        assertFalse(standby.matches(8, original, context))
    }

    @Test fun changedFocusScrollWrappingViewportOrHistoryRequiresFreshPreparation() {
        val rows = (4..8).map { row(it, (it - 8) * 70, 52) }
        val original = layout(-268, rows)
        val context = LyricOpeningContext(5, original)
        val standby = LyricOpeningStandby().apply { record(8, original, context) }
        assertFalse(standby.matches(7, original, context))
        assertFalse(standby.matches(8, layout(-268, rows.map { row(it.index, it.offset + 1, it.size) }), context))
        assertFalse(standby.matches(8, layout(-268, rows.map { row(it.index, it.offset, it.size + 20) }), context))
        assertFalse(standby.matches(8, layout(-240, rows), context))
        assertFalse(standby.matches(8, original, null))
        assertFalse(standby.matches(8, original, context.copy()))
    }

    @Test fun threeShortParagraphsArePreparedWhenTheTargetHasRoom() {
        val target = layout(-268, listOf(row(4, -280, 52), row(5, -210, 52),
            row(6, -140, 52), row(7, -70, 52), row(8, 0, 52)))
        assertEquals(5, lyricOpeningHistoryStart(target, 8, 108f, 6f))
    }

    @Test fun selectionIncludesPartialParagraphsUnderTheTopGlass() {
        val target = layout(-268, listOf(row(5, -320, 140), row(6, -162, 74),
            row(7, -70, 52), row(8, 0, 140)))
        assertEquals(6, lyricOpeningHistoryStart(target, 8, 108f, 6f))
    }

    @Test fun wrappedTranslationAndSpacingUseActualOffsetsInsteadOfShortLineEstimates() {
        val target = layout(-268, listOf(row(5, -370, 110), row(6, -242, 154),
            row(7, -70, 52), row(8, 0, 52)))
        assertEquals(6, lyricOpeningHistoryStart(target, 8, 108f, 6f))
        assertEquals(7, lyricOpeningHistoryStart(target, 8, 208f, 6f))
    }

    @Test fun blurSamplingIncludesItsEdgeButNotOffscreenBufferedRows() {
        val target = layout(-268, listOf(row(5, -240, 70), row(6, -238, 74),
            row(7, -70, 52), row(8, 0, 52)))
        assertEquals(6, lyricOpeningHistoryStart(target, 8, 108f, 6f))
        assertEquals(7, lyricOpeningHistoryStart(target, 8, 108f, 0f))
    }

    @Test fun targetGeometryScalesWithDensityAndUsesTheFocusedOffset() {
        for (scale in listOf(1, 2, 4)) {
            val shift = 33 * scale
            val target = layout(-268 * scale, (4..8).map { index ->
                row(index, (index - 8) * 70 * scale + shift, 52 * scale)
            })
            assertEquals(5, lyricOpeningHistoryStart(target, 8, 108f * scale, 6f * scale))
        }
        assertEquals(0, lyricOpeningHistoryStart(layout(-268, listOf(row(0, 0, 140))), 0, 108f, 6f))
    }

    @Test fun endpointOnlyReleasesThePreparationSnapshotAndClosingKeepsItsGeometry() {
        val target = layout(-268, (4..8).map { row(it, (it - 8) * 70, 52) })
        val first = lyricOpeningHistoryStart(target, 8, 108f, 6f)
        val history = LyricTransitionHistory()
        val context = LyricOpeningContext(first, target)
        history.prepareOpening(context)
        for (progress in listOf(.01f, .5f, .999f, .4f)) {
            history.update(progress, true)
            assertSame(context, history.openingContext)
        }
        history.update(1f, true)
        assertNull(history.openingContext)
        history.update(.9f, true, closingWindow = true)
        assertTrue(history.preservingCloseWindow)
        history.update(.4f, true)
        assertTrue(history.preservingCloseWindow)
        history.update(1f, true)
        assertFalse(history.preservingCloseWindow)
    }

    @Test fun widerOpeningContextKeepsTheOriginalCompactHeightForTheOpeningBrake() {
        val target = layout(-268, (4..8).map { row(it, (it - 8) * 70, 52) })
        val compact = layout(-268, listOf(row(6, -268, 52), row(7, -198, 52), row(8, -128, 52)))
        val context = LyricOpeningContext(lyricOpeningHistoryStart(target, 8, 108f, 6f), compact)
        val travel = PhoneLyricsSpatialTravel()
        travel.record(context.compactLayout, Density(1f), LyricBlurProtection(null, true))
        travel.capture()
        assertEquals(5, context.first)
        assertEquals(122f, travel.compactHeightPx, 0f)
    }

    private fun row(index: Int, offset: Int, size: Int): LazyListItemInfo = proxy(mapOf(
        "getIndex" to index, "getOffset" to offset, "getSize" to size, "getContentType" to false))

    private fun layout(start: Int, rows: List<LazyListItemInfo>): LazyListLayoutInfo = proxy(mapOf(
        "getVisibleItemsInfo" to rows, "getViewportStartOffset" to start,
        "getViewportEndOffset" to 900, "getViewportSize-YbymL2g" to ((390L shl 32) or 900L),
        "getMainAxisItemSpacing" to 18, "getTotalItemsCount" to 20))

    private inline fun <reified T> proxy(values: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: error("未预期的布局读取：${method.name}")
        } as T
}
