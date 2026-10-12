package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlayerLyricsBlurProtectionTest {
    @Test fun wideningOnlyMovesTheUpperEdgeAndPreservesControlsBoundary() {
        for (scale in listOf(1f, 2.75f)) for (boundary in listOf(320f, 600f, 1200f)) {
            val end = boundary * scale
            val start = playerControlsBlurStart(end, 88f * scale, 128f * scale,
                LyricBlurProtection(null, true), 8f * scale)
            assertEquals(end - 128f * scale, start, .001f)
            assertEquals(128f * scale, end - start, .001f)
        }
    }

    @Test fun newBlurCannotReachFocusedParagraphOnShortScreensOrWithWrappedRows() {
        val original = 512f
        for (focusBottom in listOf(470f, 490f, 510f, 535f, 680f)) {
            val start = playerControlsBlurStart(600f, 88f, 128f,
                LyricBlurProtection(focusBottom, true), 8f)
            assertTrue(start <= original)
            assertTrue(start >= 472f)
            if (focusBottom + 8f < original) assertTrue(start >= focusBottom + 8f)
            else assertEquals(original, start, 0f)
        }
    }

    @Test fun layerHandoffsAndOtherPlayerLayoutsKeepOriginalBlurWidth() {
        for (protection in listOf(null, LyricBlurProtection.HANDOFF)) {
            assertEquals(512f, playerControlsBlurStart(600f, 88f, 128f, protection, 8f), 0f)
        }
    }

    @Test fun focusBoundIncludesListPaddingTranslationAndResidualRowMotion() {
        val item = proxy<LazyListItemInfo>(mapOf("getIndex" to 7, "getOffset" to -20, "getSize" to 120))
        val layout = proxy<LazyListLayoutInfo>(mapOf("getVisibleItemsInfo" to listOf(item),
            "getViewportStartOffset" to -320))
        val protection = lyricBlurProtection(layout, 7, 15f, stable = true)
        assertEquals(435f, protection.focusBottom!!, 0f)
        assertTrue(protection.allowWidening)
        assertEquals(LyricBlurProtection.HANDOFF, lyricBlurProtection(layout, 7, 15f, stable = false))
        assertNull(lyricBlurProtection(layout, 8, 15f, stable = true).focusBottom)
    }

    private inline fun <reified T> proxy(values: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: error("未预期的布局读取：${method.name}")
        } as T
}
