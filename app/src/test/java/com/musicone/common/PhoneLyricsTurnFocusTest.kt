package com.musicone

import androidx.compose.foundation.lazy.LazyListItemInfo
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsTurnFocusTest {
    @Test fun expandedViewportAndRowMotionAreAlreadyIncludedInTheSharedFocus() {
        val item = Proxy.newProxyInstance(LazyListItemInfo::class.java.classLoader,
            arrayOf(LazyListItemInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getOffset" -> -20
                "getSize" -> 120
                else -> error("未预期的布局读取：${method.name}")
            }
        } as LazyListItemInfo
        val focus = PhoneLyricsTurnFocus()
        focus.record(3, 5000, -1820, item, 15f, true)
        assertEquals(LyricBoundaryEdges(1815f, 1935f), focus.read(3, 5000f))
        assertNull(focus.read(4, 5000f))
        assertNull(focus.read(3, 1800f))
        focus.record(3, 5000, -1820, item, 15f, false)
        assertNull(focus.read(3, 5000f))
        focus.record(3, 5000, -1820, null, 15f, true)
        assertNull(focus.read(3, 5000f))
    }
}
