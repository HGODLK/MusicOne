package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCapsuleSelectionTest {
    @Test fun maskMovesAcrossBothLabelsWithoutWaitingForPageSelection() {
        assertEquals(Rect(0f, 0f, 118f, 52f), navigationCapsuleSelectionBounds(236f, 52f, 0f))
        assertEquals(Rect(59f, 0f, 177f, 52f), navigationCapsuleSelectionBounds(236f, 52f, .5f))
        assertEquals(Rect(118f, 0f, 236f, 52f), navigationCapsuleSelectionBounds(236f, 52f, 1f))
        assertEquals(Rect(29.5f, 0f, 147.5f, 52f), navigationCapsuleSelectionBounds(236f, 52f, .25f))
    }

    @Test fun 手机胶囊比迷你播放器矮且平板保持原尺寸() {
        val phone = navigationCapsuleMetrics(phone = true)
        val tablet = navigationCapsuleMetrics(phone = false)
        assertEquals(56.dp, phone.innerHeight + phone.innerPadding * 2)
        assertEquals(246.dp, tablet.width)
        assertEquals(62.dp, tablet.innerHeight + tablet.innerPadding * 2)
    }

    @Test fun overscrollCannotMoveMaskOutsideTheDock() {
        assertEquals(Rect(0f, 0f, 100f, 44f), navigationCapsuleSelectionBounds(200f, 44f, -1f))
        assertEquals(Rect(100f, 0f, 200f, 44f), navigationCapsuleSelectionBounds(200f, 44f, 2f))
    }

    @Test fun allPlatformColorsAreTranslucentWhileNeutralRemainsUnchanged() {
        listOf(Color(0xFFEC4141), Color(0xFF31C27C), Color(0xFF009AF3)).forEach { color ->
            assertTrue(navigationCapsuleSelectionColor(false, color).alpha in .54f.. .58f)
        }
        assertEquals(MusicOneNeutralContainer, navigationCapsuleSelectionColor(true, MusicOneNeutralContainer))
        assertEquals(Color(0xFFEC4141).copy(alpha = .56f), navigationCapsuleSelectionColor(false, Color(0xFFEC4141)))
    }
}
