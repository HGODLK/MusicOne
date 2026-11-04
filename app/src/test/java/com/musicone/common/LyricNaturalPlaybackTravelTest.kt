package com.musicone

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LyricNaturalPlaybackTravelTest {
    @Test fun nextLineOutsideTheViewportStartsAtTheFullMeasuredParagraphBottom() {
        val longRow = row(8, -35, 1250)
        assertEquals(1233f, lyricNaturalStepTravel(layout(listOf(longRow)), 9)!!, 0f)
        val nextRow = row(9, 347, 180)
        assertEquals(347f, lyricNaturalStepTravel(layout(listOf(longRow, nextRow)), 9)!!, 0f)
        assertEquals(200f, lyricPlaybackMotionStiffness(1233f, LyricPlaybackMotionPurpose.PLAYBACK), 0f)
    }

    @Test fun interruptedNaturalFollowUsesTheMeasuredRemainingDistance() {
        assertEquals(250f, lyricNaturalStepTravel(layout(listOf(row(8, -1018, 1250))), 9)!!, 0f)
        assertEquals(-12f, lyricNaturalStepTravel(layout(listOf(row(9, -12, 180))), 9)!!, 0f)
        assertNull(lyricNaturalStepTravel(layout(emptyList()), 9))
    }

    private fun row(index: Int, offset: Int, height: Int): LazyListItemInfo = proxy(mapOf(
        "getIndex" to index, "getOffset" to offset, "getSize" to height))
    private fun layout(rows: List<LazyListItemInfo>): LazyListLayoutInfo = proxy(mapOf(
        "getVisibleItemsInfo" to rows, "getMainAxisItemSpacing" to 18))
    private inline fun <reified T> proxy(values: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: error("未预期的布局读取：${method.name}")
        } as T
}
