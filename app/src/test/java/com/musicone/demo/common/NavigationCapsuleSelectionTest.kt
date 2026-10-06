package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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

    @Test fun overscrollCannotMoveMaskOutsideTheDock() {
        assertEquals(Rect(0f, 0f, 100f, 44f), navigationCapsuleSelectionBounds(200f, 44f, -1f))
        assertEquals(Rect(100f, 0f, 200f, 44f), navigationCapsuleSelectionBounds(200f, 44f, 2f))
    }

    @Test fun allPlatformColorsAreTranslucentWhileNeutralRemainsUnchanged() {
        listOf(NeteaseMusicThemeColor, QqMusicThemeColor, KugouMusicThemeColor).forEach { color ->
            assertTrue(navigationCapsuleSelectionColor(false, color, null).alpha in .54f.. .58f)
        }
        assertEquals(MusicOneNeutralContainer, navigationCapsuleSelectionColor(true, MusicOneNeutralContainer, Color.Red))
        assertTrue(navigationCapsuleSelectionColor(false, Color.Red, Color.White).alpha in .22f.. .26f)
    }
}
