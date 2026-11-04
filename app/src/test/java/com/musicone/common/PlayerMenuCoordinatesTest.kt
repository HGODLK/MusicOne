package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlayerMenuCoordinatesTest {
    private val host = Rect(20f, 40f, 420f, 940f)

    @Test fun firstLandscapeSampleUsesDeclaredDirectionWithoutQueryingTheRootTransform() {
        for (rotation in listOf(90, -90)) {
            val canvas = coordinateStub()
            val surface = coordinateStub(canvas)
            val sampling = PlayerMenuSampling(PlayerMenuCoordinates(host, rotation))
            sampling.canvas = canvas
            val origin = sampling.pointInSurface(surface, host.topLeft)!!
            val x = sampling.pointInSurface(surface, host.topLeft + Offset(1f, 0f))!! - origin
            val y = sampling.pointInSurface(surface, host.topLeft + Offset(0f, 1f))!! - origin
            assertEquals(if (rotation > 0) Offset(0f, -1f) else Offset(0f, 1f), x)
            assertEquals(if (rotation > 0) Offset(1f, 0f) else Offset(-1f, 0f), y)
            // 改窗后保留已经放置的画布，不等待第二次 onPlaced 才能采样。
            val resized = Rect(30f, 50f, 530f, 1050f)
            sampling.geometry = PlayerMenuCoordinates(resized, rotation)
            assertSame(canvas, sampling.canvas)
            assertEquals(Offset(500f, 250f), sampling.pointInSurface(surface, resized.center))
        }
    }

    private fun coordinateStub(expectedCanvas: LayoutCoordinates? = null): LayoutCoordinates =
        Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader, arrayOf(LayoutCoordinates::class.java)) { _, method, args ->
            when {
                method.name == "isAttached" -> true
                method.name.startsWith("localPositionOf") -> {
                    assertSame(expectedCanvas, args!![0])
                    args[1]
                }
                // 首帧测试刻意不提供父级或根矩阵；采样必须只经过菜单内部坐标。
                else -> error("Unexpected coordinate query: ${method.name}")
            }
        } as LayoutCoordinates

    @Test fun landscapeQueueRowBecomesHorizontalBeforeMenuAvoidance() {
        val normal = PlayerMenuCoordinates(host, 90)
        val reversed = PlayerMenuCoordinates(host, -90)
        // 同一条横向歌曲行在物理窗口中表现为竖长条。
        val normalRow = normal.rectInMenu(Rect(290f, 56f, 350f, 924f))
        val reversedRow = reversed.rectInMenu(Rect(90f, 56f, 150f, 924f))
        assertEquals(Rect(16f, 70f, 884f, 130f), normalRow)
        assertEquals(normalRow, reversedRow)
        val region = queueSongMenuRegion(900f, 400f, normalRow, 12f, 8f)
        assertFalse(region.above)
        assertTrue(region.bounds.height > 240f)
        assertFalse(region.bounds.overlaps(normalRow))
    }

    @Test fun physicalTouchAndMenuPivotUseTheSameRotatedCanvas() {
        val normal = PlayerMenuCoordinates(host, 90)
        val reversed = PlayerMenuCoordinates(host, -90)
        assertEquals(Offset(0f, 400f), normal.pointInMenu(host.topLeft))
        assertEquals(Offset(900f, 0f), reversed.pointInMenu(host.topLeft))
        assertEquals(Offset(450f, 200f), normal.pointInMenu(host.center))
        assertEquals(normal.pointInMenu(host.center), reversed.pointInMenu(host.center))
        assertEquals(Rect(0f, 0f, 900f, 400f), normal.localBounds)
    }

    @Test fun portraitKeepsOriginalHostOffsetAndMenuGeometry() {
        val portrait = PlayerMenuCoordinates(host, 0)
        assertEquals(Rect(16f, 60f, 384f, 120f), portrait.rectInMenu(Rect(36f, 100f, 404f, 160f)))
        assertEquals(Rect(0f, 0f, 400f, 900f), portrait.localBounds)
    }
}
