package com.musicone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySegmentSwipeTest {
    @Test
    fun 拖过四十八dp即切换() {
        assertTrue(librarySegmentSwipeCommits(-48f, -48f, 0f))
        assertTrue(librarySegmentSwipeCommits(60f, 60f, -100f))
    }

    @Test
    fun 短划需要同向足够速度() {
        assertTrue(librarySegmentSwipeCommits(-20f, -20f, -300f))
        assertFalse(librarySegmentSwipeCommits(-20f, -20f, -200f))
        assertFalse(librarySegmentSwipeCommits(-10f, -10f, -800f))
        // 回勾反向甩动不按原方向切换。
        assertFalse(librarySegmentSwipeCommits(-20f, -20f, 400f))
    }
}
