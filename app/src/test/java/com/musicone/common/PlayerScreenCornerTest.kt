package com.musicone

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScreenCornerTest {
    @After fun restore() {
        MiniPlayerSurfaceCorner.dp = 19f
        MiniPlayerSurfaceCorner.screenDp = 0f
    }

    @Test fun 四角取最小值且任一角缺失视为直角() {
        assertEquals(126, reportedScreenCornerPx(listOf(126, 126, 135, 135)))
        assertEquals(0, reportedScreenCornerPx(listOf(126, null, 135, 135)))
        assertEquals(0, reportedScreenCornerPx(listOf(0, 0, 0, 0)))
        assertEquals(0, reportedScreenCornerPx(emptyList()))
    }

    @Test fun 只有实测过的华为P50E使用登记值并随分辨率缩放() {
        assertEquals(126.22f, knownScreenCornerPx("HUAWEI", "ABR-AL80", 1224), .001f)
        assertEquals(126.22f, knownScreenCornerPx("huawei", "ABR_AL80", 1224), .001f)
        assertEquals(63.11f, knownScreenCornerPx("HUAWEI", "ABR-AL80", 612), .001f)
        assertEquals(0f, knownScreenCornerPx("HUAWEI", "ABR-AL00", 1224), 0f)
        assertEquals(0f, knownScreenCornerPx("Xiaomi", "ABR-AL80", 1224), 0f)
        assertEquals(0f, knownScreenCornerPx("HUAWEI", "ABR-AL80", 0), 0f)
    }

    @Test fun 旧系统上下圆角未设置时用通用值() {
        assertEquals(60, legacyScreenCornerPx(60, 72, 40))
        assertEquals(40, legacyScreenCornerPx(0, 72, 40))
        assertEquals(0, legacyScreenCornerPx(0, 0, 0))
    }

    @Test fun 展开途中过渡到屏幕圆角且完全展开后回到直角() {
        MiniPlayerSurfaceCorner.dp = 20f
        MiniPlayerSurfaceCorner.screenDp = 36f
        assertEquals(20f, playerSurfaceCorner(0f), 0f)
        assertEquals(28f, playerSurfaceCorner(.5f), .001f)
        assertEquals(0f, playerSurfaceCorner(1f), 0f)
        MiniPlayerSurfaceCorner.screenDp = 0f
        assertEquals(10f, playerSurfaceCorner(.5f), .001f)
    }
}
