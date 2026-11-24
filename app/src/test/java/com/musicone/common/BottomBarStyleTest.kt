package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarStyleTest {
    @Test
    fun hiddenCapsuleFallsBackToRowLayoutAndKeepsReverse() {
        val classicReversed = ExperienceOptions(classicBottomBar = true, reverseBottomBar = true)
        // 胶囊开着：经典布局，反转同样生效（只镜像播放条）。
        assertEquals(BottomBarStyle(classic = true, mirrored = true), bottomBarStyleFor(classicReversed))
        // 关闭胶囊：按并排处理，反转生效，保存的经典选择不变。
        val hidden = classicReversed.copy(hideNavigationCapsule = true)
        assertEquals(BottomBarStyle(classic = false, mirrored = true), bottomBarStyleFor(hidden))
        assertEquals(true, hidden.classicBottomBar)
    }
}
