package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricFirstPlacementTest {
    @Test fun visibleButMisplacedLineCannotCompleteFirstEntrance() {
        for (geometry in listOf(LyricPlacementGeometry(400, 800, 180), LyricPlacementGeometry(900, 1100, 280))) {
            val placement = LyricFirstPlacement()
            assertFalse(placement.confirm(geometry, 65, 0f, false))
            assertFalse(placement.ready(geometry))
            assertFalse(placement.confirm(geometry, 0, 12f, false))
            assertFalse(placement.confirm(geometry, 0, 0f, true))
            assertFalse(placement.confirm(geometry, null, 0f, false))
            assertTrue(placement.confirm(geometry, 0, 0f, false))
            assertTrue(placement.ready(geometry))
        }
    }

    @Test fun widthHeightAndReadingAnchorChangesBeforePresentationRequireNewPlacement() {
        val placement = LyricFirstPlacement()
        val phone = LyricPlacementGeometry(400, 800, 180)
        placement.confirm(phone, 0, 0f, false)
        assertFalse(placement.ready(phone.copy(width = 900)))
        assertFalse(placement.ready(phone.copy(height = 1100)))
        assertFalse(placement.ready(phone.copy(anchor = 280)))
        assertFalse(placement.confirm(phone.copy(height = 0), 0, 0f, false))
        // 完成后的普通行内运动不使首次定位重新失效。
        assertTrue(placement.ready(phone))
    }

    @Test fun preparedSeekTargetStaysReadyWhenOwnershipReturnsToPlayback() {
        val placement = LyricFirstPlacement()
        val geometry = LyricPlacementGeometry(900, 1100, 280)
        assertTrue(placement.visible(geometry, enabled = true, allowed = false))
        // 预备窗口的真实绘制完成确认，交回正常播放时不再先隐藏一帧。
        placement.confirm(geometry, 0, 0f, false)
        assertTrue(placement.visible(geometry, enabled = true, allowed = true))
    }

    @Test fun displayedWindowNeverReentersFirstPlacementDuringPlaybackOrLayoutHandoff() {
        val placement = LyricFirstPlacement()
        val geometry = LyricPlacementGeometry(400, 800, 180)
        assertFalse(placement.visible(geometry, enabled = true, allowed = true))
        placement.confirm(geometry, 0, 0f, false)
        placement.markPresented()
        for (changed in listOf(geometry, geometry.copy(anchor = 181), geometry.copy(width = 900), geometry.copy(height = 0))) {
            for (allowed in listOf(false, true, false, true)) {
                // 下一句尚在滚动时不能使画面消失，也不能要求首次定位重新归零。
                placement.confirm(changed, 73, 12f, true)
                assertTrue(placement.ready(changed))
                assertTrue(placement.visible(changed, enabled = true, allowed = allowed))
            }
        }
    }

    @Test fun alreadyVisibleExternalSeekDoesNotGetHiddenAfterItsOwnerFinishes() {
        val placement = LyricFirstPlacement()
        val geometry = LyricPlacementGeometry(400, 800, 180)
        assertTrue(placement.visible(geometry, enabled = true, allowed = false))
        placement.markPresented()
        assertTrue(placement.visible(geometry, enabled = true, allowed = true))
        assertFalse(LyricFirstPlacement().ready(geometry))
    }
}
