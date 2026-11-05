package com.musicone

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsMetricsTest {
    @Test
    fun compactSavesSeventyTwoDp() {
        val normal = PlayerControlsMetrics.Normal
        val compact = PlayerControlsMetrics.Compact
        val saved = (normal.timeline + normal.row + normal.bottomRow) -
            (compact.timeline - compact.timeLabelsLift + compact.row + compact.bottomRow)
        assertEquals(72f, saved.value, 0f)
    }

    @Test
    fun compactControlsStayInsideTheirRows() {
        val compact = PlayerControlsMetrics.Compact
        assertTrue(compact.playButton <= compact.row && compact.playButton <= compact.tabletRow)
        // 固定按钮行 58dp、按钮 56dp，上缘伸进控制行 7dp，不能碰到音质和播放模式按钮。
        assertTrue((compact.row - compact.sideButton) / 2 >= 7.dp)
    }

    @Test
    fun estimatedHeightMatchesMeasuredLayout() {
        // 时间文字约占 15dp，估算偏小时文字和飞行落点都会偏低。
        listOf(PlayerControlsMetrics.Normal, PlayerControlsMetrics.Compact).forEach {
            val measured = it.timeline + 15.dp - it.timeLabelsLift + it.row + it.bottomRow
            assertTrue((it.estimatedHeight - measured).value in -3f..3f)
        }
    }

    @Test
    fun heightMemorySeparatesDensityAndCompactMode() {
        val density = Density(2.37f, 1.13f)
        assertNull(PlayerControlsHeightMemory.read(density, compact = true))
        PlayerControlsHeightMemory.write(density, compact = true, heightPx = 400)
        PlayerControlsHeightMemory.write(density, compact = true, heightPx = 0)
        assertEquals(400, PlayerControlsHeightMemory.read(density, compact = true))
        assertNull(PlayerControlsHeightMemory.read(density, compact = false))
        assertNull(PlayerControlsHeightMemory.read(Density(2.37f, 1f), compact = true))
    }
}
