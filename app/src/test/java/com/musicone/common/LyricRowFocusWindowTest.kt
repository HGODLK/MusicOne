package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricRowFocusWindowTest {
    @Test fun 焦点窗口内保持原值() {
        for (current in 7..12) assertEquals(current, lyricRowFocusWindow(10, current))
    }

    @Test fun 远处焦点收进窗口且单步切句时值不变() {
        assertEquals(7, lyricRowFocusWindow(10, 0))
        assertEquals(7, lyricRowFocusWindow(10, 6))
        assertEquals(12, lyricRowFocusWindow(10, 13))
        assertEquals(12, lyricRowFocusWindow(10, 40))
    }

    @Test fun 收窗后行内判断与真实焦点一致() {
        for (index in 0..20) for (current in 0..30) {
            val window = lyricRowFocusWindow(index, current)
            assertEquals(index == current, index == window)
            assertEquals(index <= current, index <= window)
            assertEquals(index in (current - 1)..(current + 1), index in (window - 1)..(window + 1))
            assertEquals(index in (current - 1)..(current + 2), index in (window - 1)..(window + 2))
            assertEquals(index == current - 1, index == window - 1)
            assertEquals(index == current + 2, index == window + 2)
            assertEquals(lyricBlurDp(index, current), lyricBlurDp(index, window))
        }
    }
}
