package com.musicone

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerQueueSettlementTest {
    @Test fun phoneAndTabletQueueFinishesWithoutAPixelSizedSnap() {
        for (target in listOf(0f, 1f)) for (start in listOf(.05f, .4f, .9f)) {
            for (velocity in listOf(-6f, 0f, 6f)) {
                val animation = TargetBasedAnimation(PlayerQueueGestureAnimation, Float.VectorConverter,
                    start, target, velocity)
                val lastFrame = animation.getValueFromNanos((animation.durationNanos - 16_666_667L).coerceAtLeast(0L))
                for (height in listOf(680f, 1_600f, 3_840f)) {
                    assertTrue("收尾不能吸附可见距离: start=$start target=$target velocity=$velocity height=$height",
                        abs(lastFrame - target) * height < 1f)
                }
                assertEquals(target, animation.getValueFromNanos(animation.durationNanos), 0f)
            }
        }
    }
}
