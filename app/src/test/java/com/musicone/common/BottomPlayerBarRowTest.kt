package com.musicone

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomPlayerBarRowTest {
    @Test fun 播放条让出胶囊宽度并在胶囊收起后占满整行() {
        assertEquals(248, bottomBarRowMiniWidth(360, 104, 8, 1f))
        assertEquals(304, bottomBarRowMiniWidth(360, 104, 8, .5f))
        assertEquals(360, bottomBarRowMiniWidth(360, 104, 8, 0f))
        assertEquals(360, bottomBarRowMiniWidth(360, 104, 8, -1f))
        assertEquals(248, bottomBarRowMiniWidth(360, 104, 8, 2f))
    }

    @Test fun 胶囊贴着播放条外侧且反转时整行镜像() {
        // 完全显示：胶囊距内容边缘 12，与播放条玻璃（槽位内缩 12）相隔 8。
        assertEquals(244, bottomBarRowCapsuleX(0, 360, 104, 12, 8, 1f, false))
        assertEquals(12, bottomBarRowCapsuleX(0, 360, 104, 12, 8, 1f, true))
        // 完全收起：被变宽的播放条推到内容区之外。
        assertEquals(356, bottomBarRowCapsuleX(0, 360, 104, 12, 8, 0f, false))
        assertEquals(-100, bottomBarRowCapsuleX(0, 360, 104, 12, 8, 0f, true))
        // 平板内容区居中时整体平移。
        assertEquals(344, bottomBarRowCapsuleX(100, 360, 104, 12, 8, 1f, false))
    }

    @Test fun 并排胶囊与迷你播放条等高同圆角且经典圆角不变() {
        val barHeight = MiniPlayerLayoutMetrics.artwork + MiniPlayerLayoutMetrics.innerPadding * 2
        listOf(true, false).forEach { phone ->
            val metrics = rowNavigationCapsuleMetrics(phone)
            assertEquals(barHeight, metrics.innerHeight + metrics.innerPadding * 2)
            assertEquals(MiniPlayerLayoutMetrics.outerPadding, metrics.topGap)
            assertEquals(MiniPlayerLayoutMetrics.outerPadding, metrics.bottomGap)
            assertEquals(BottomBarStyle(classic = false, mirrored = false).surfaceCorner, metrics.outerRadius)
            // 手机只留图标，平板保留文字。
            assertEquals(!phone, metrics.labels)
        }
        assertEquals(19.dp, BottomBarStyle(classic = true, mirrored = false).surfaceCorner)
        assertEquals(true, navigationCapsuleMetrics(phone = true).labels)
        assertEquals(navigationCapsuleMetrics(phone = false).width, rowNavigationCapsuleMetrics(phone = false).width)
    }
}
