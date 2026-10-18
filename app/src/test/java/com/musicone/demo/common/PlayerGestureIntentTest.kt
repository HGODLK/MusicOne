package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerGestureIntentTest {
    @Test fun longPullKeepsItsTargetDespiteFastLiftHook() {
        val drag = PlayerGestureDisplacement(false)
        drag.move(300f)
        drag.move(-9f)
        assertEquals(9f, drag.reverseDistancePx, 0f)
        // 下拉过半屏（progress <= 0.5f，即下拉进度 >= 0.5f）位置优先，轻微回勾不改变关闭目标
        assertTrue(shouldDismissPlayer(.2f, -563f, true, drag.distancePx, drag.reverseDistancePx))
        assertTrue(shouldDismissPlayer(.45f, -563f, true, drag.distancePx, drag.reverseDistancePx))
        // 未过半屏且速度反向推回时，不再仅凭位移误关
        assertFalse(shouldDismissPlayer(.8f, -563f, true, 60f, drag.reverseDistancePx))
    }

    @Test fun deliberateReturnAcrossThePositionThresholdRestoresTheOriginalSide() {
        val opening = PlayerGestureDisplacement(false)
        opening.move(180f)
        opening.move(-120f)
        assertFalse(shouldOpenPlayer(.4f, 900f, true, -opening.distancePx, opening.reverseDistancePx))
        assertTrue(shouldOpenPlayer(.7f, 900f, true, -opening.distancePx, opening.reverseDistancePx))
        val closing = PlayerGestureDisplacement(true)
        closing.move(-180f)
        closing.move(120f)
        assertFalse(lyricsDragTarget(.4f, -900f, -closing.distancePx, true,
            reverseDistanceDp = closing.reverseDistancePx))
        assertTrue(lyricsDragTarget(.7f, -900f, -closing.distancePx, true,
            reverseDistanceDp = closing.reverseDistancePx))
    }

    @Test fun cancellationKeepsReachedEndpointsAndRestoresUnfinishedDrags() {
        assertTrue(shouldDismissPlayer(0f, 0f, false, 0f))
        assertTrue(shouldOpenPlayer(1f, 0f, false, 0f))
        assertFalse(shouldDismissPlayer(.4f, 0f, false, 300f))
        assertFalse(shouldOpenPlayer(.6f, 0f, false, -300f))
        assertTrue(lyricsDragTarget(1f, 0f, original = false, completed = false))
        assertFalse(lyricsDragTarget(0f, 0f, original = true, completed = false))
        assertTrue(lyricsDragTarget(.4f, 0f, original = true, completed = false))
    }

    @Test fun clearShortSwipeStillWorksAndTinyTapDoesNotCommit() {
        // 播放页短划门槛收紧至 32dp 与 650dp/s
        assertFalse(shouldOpenPlayer(.02f, -650f, true, -31f))
        assertFalse(shouldOpenPlayer(.02f, -649f, true, -32f))
        assertTrue(shouldOpenPlayer(.02f, -650f, true, -32f))
        assertTrue(shouldOpenPlayer(.02f, -800f, true, -36f))
        // 播放页慢拖未过半屏（0.98f 展开度，即下拉 0.02f）即使达到 48dp 也不提交关闭
        assertFalse(shouldDismissPlayer(.98f, 0f, true, 48f))
        // 慢拖过半屏（0.45f 展开度，即下拉 0.55f）则正常提交关闭
        assertTrue(shouldDismissPlayer(.45f, 0f, true, 48f))
        assertFalse(shouldOpenPlayer(.02f, -1200f, true, -4f))
        // 快速长划即使松手采样衰减到零，也按有效长距离提交；中等慢拖仍安全回弹。
        assertTrue(shouldDismissPlayer(.8f, 0f, true, 96f))
        assertTrue(shouldOpenPlayer(.2f, 0f, true, -96f))
        assertFalse(shouldDismissPlayer(.8f, 0f, true, 95f))
        assertFalse(shouldOpenPlayer(.2f, 0f, true, -95f))
        // 歌词短划门槛使用 24dp 与 500dp/s
        assertFalse(lyricsDragTarget(.2f, -500f, -23f))
        assertFalse(lyricsDragTarget(.2f, -490f, -24f))
        assertTrue(lyricsDragTarget(.2f, -500f, -24f))
        // 歌词慢拖未过半屏不提交
        assertFalse(lyricsDragTarget(.4f, 0f, -40f))
        assertTrue(lyricsDragTarget(.6f, 0f, -40f))
    }

    @Test fun settlingVelocityCannotMoveAwayFromTheSelectedEndpoint() {
        assertEquals(0f, playerGestureSettlingVelocity(true, -2f), 0f)
        assertEquals(0f, playerGestureSettlingVelocity(false, 2f), 0f)
        assertEquals(2f, playerGestureSettlingVelocity(true, 2f), 0f)
        assertEquals(-2f, playerGestureSettlingVelocity(false, -2f), 0f)
    }
}
