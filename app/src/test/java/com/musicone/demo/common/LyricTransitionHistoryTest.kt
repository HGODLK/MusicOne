package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LyricTransitionHistoryTest {
    @Test fun fiveBufferedParagraphsOnlyKeepTwoShortPredecessors() {
        val first = lyricTransitionHistoryStart(8, 73f, 73f, 73f, 73f, 1f)
        assertEquals(6, first)
        assertEquals(listOf(6, 7), (3 until 8).filter { it >= first })
    }

    @Test fun wrappedOriginalOrTranslationKeepsOneWholeParagraph() {
        assertEquals(7, lyricTransitionHistoryStart(8, 115f, 73f, 73f, 73f, 1f))
        assertEquals(7, lyricTransitionHistoryStart(8, 73f, 94f, 73f, 73f, 1f))
        assertEquals(7, lyricTransitionHistoryStart(8, 280f, 280f, 73f, 73f, 1f))
    }

    @Test fun decisionsFollowMeasuredDensityAndFontScale() {
        for (scale in listOf(1f, 2.75f, 4f)) {
            assertEquals(6, lyricTransitionHistoryStart(8, 73f * scale, 74f * scale,
                73f * scale, 73f * scale, scale))
            assertEquals(7, lyricTransitionHistoryStart(8, 94f * scale, 73f * scale,
                73f * scale, 73f * scale, scale))
        }
    }

    @Test fun songBeginningAndMissingMeasurementsDoNotRemoveTheFocusedParagraph() {
        assertEquals(0, lyricTransitionHistoryStart(0, null, null, null, null, 1f))
        assertEquals(0, lyricTransitionHistoryStart(1, 73f, null, 73f, null, 1f))
        assertEquals(7, lyricTransitionHistoryStart(8, null, null, null, null, 1f))
    }

    @Test fun heldReversalKeepsWholeParagraphsAndExpandedBrowsingRestoresAllHistory() {
        val layout = proxy<LazyListLayoutInfo>(mapOf(
            "getViewportSize-YbymL2g" to ((390L shl 32) or 900L),
            "getVisibleItemsInfo" to (3..10).map { index ->
                proxy<LazyListItemInfo>(mapOf("getIndex" to index, "getSize" to 73, "getContentType" to true))
            },
        ))
        val history = LyricTransitionHistory()
        val density = Density(1f)
        history.prepare(layout, 8, density)
        for (progress in listOf(.01f, .4f, .8f, .4f)) {
            history.update({ layout }, 8, density, progress, enabled = true)
            assertEquals(listOf(6, 7), (3 until 8).filter(history::draws))
            assertTrue(history.draws(8))
            assertTrue(history.draws(9))
        }
        history.update({ layout }, 8, density, 1f, enabled = true)
        assertTrue((3..10).all(history::draws))
        history.update({ layout }, 8, density, .4f, enabled = false)
        assertTrue((3..10).all(history::draws))
    }

    private inline fun <reified T> proxy(values: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: error("未预期的布局读取：${method.name}")
        } as T
}
