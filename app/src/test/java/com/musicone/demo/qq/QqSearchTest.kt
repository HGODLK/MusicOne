package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline

class QqSearchTest {
    @Test fun tabletContentOnlyChangesSizeAtHandoff() {
        val menu = 480.dp to 360.dp
        val tablet = 1280.dp to 800.dp
        assertEquals(menu, searchContentSize(menu, tablet, false))
        assertEquals(tablet, searchContentSize(menu, tablet, true))
        val phone = 400.dp to 720.dp
        assertEquals(360.dp to 260.dp, searchContentSize(360.dp to 260.dp, phone, false))
        assertEquals(phone, searchContentSize(360.dp to 260.dp, phone, true))
    }

    @Test fun revealUsesRoundedOutlineAtMenuAndFullScreen() {
        var frame = QqSearchGeometry(480.dp, 360.dp, 32.dp, 10.dp, 26.dp, 480.dp, 360.dp)
        val shape = QqSearchRevealShape { frame }
        val menu = shape.createOutline(Size(1280f, 800f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(768f, menu.roundRect.left, .01f)
        assertEquals(370f, menu.roundRect.bottom, .01f)
        frame = frame.copy(width = 1280.dp, height = 800.dp, right = 0.dp, top = 0.dp, corner = 0.dp)
        val full = shape.createOutline(Size(1280f, 800f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(0f, full.roundRect.left, .01f)
        assertEquals(800f, full.roundRect.bottom, .01f)
    }

    @Test fun fixedMenuContentUsesViewportCoordinates() {
        val phoneMenu = QqSearchGeometry(367.dp, 204.dp, 20.dp, 10.dp, 26.dp, 367.dp, 204.dp)
        assertEquals(20.dp, searchContentOffset(407.dp, phoneMenu, 1f))
        val tabletMenu = QqSearchGeometry(480.dp, 360.dp, 32.dp, 10.dp, 26.dp, 480.dp, 360.dp)
        assertEquals(768.dp, searchContentOffset(1280.dp, tabletMenu, 1f))
        val full = tabletMenu.copy(width = 1280.dp, right = 0.dp)
        assertEquals(0.dp, searchContentOffset(1280.dp, full, 1f))
    }
    @Test fun topFadeIsOpaqueAboveTabsAndDisappearsContinuouslyBelow() {
        assertEquals(1f, searchTopWhiteAlpha(0f, 32f, 64f), .001f)
        assertEquals(1f, searchTopWhiteAlpha(32f, 32f, 64f), .001f)
        assertEquals(.5f, searchTopWhiteAlpha(48f, 32f, 64f), .001f)
        assertEquals(0f, searchTopWhiteAlpha(64f, 32f, 64f), .001f)
        assertEquals(0f, searchTopWhiteAlpha(100f, 32f, 64f), .001f)
    }
    @Test fun suggestionsGrowWithContentUntilTheHalfScreenLimit() {
        assertEquals(116f, searchSuggestionHeight(1, false, 48f), .01f)
        assertEquals(164f, searchSuggestionHeight(2, false, 48f), .01f)
        assertEquals(116f, searchSuggestionHeight(0, false, 48f), .01f)
        assertEquals(360f, searchMenuHeight(720f, 250f, 10f,
            searchSuggestionHeight(16, false, 48f)), .01f)
        assertEquals(178f, searchMenuHeight(720f, 520f, 10f,
            searchSuggestionHeight(16, false, 48f)), .01f)
    }

    @Test fun miniPlayerTextFollowsLocalBrightnessWithHysteresis() {
        assertTrue(miniPlayerUsesLightInk(.08f, false))
        assertFalse(miniPlayerUsesLightInk(.8f, true))
        assertTrue(miniPlayerUsesLightInk(.18f, true))
        assertFalse(miniPlayerUsesLightInk(.18f, false))
    }
    @Test fun submittedHistoryDeduplicatesMovesToFrontAndKeepsSixteen() {
        val old = (1..16).map { "关键词$it" }
        val recent = updatedSearchHistory(old, "  关键词8  ")
        assertEquals(16, recent.size)
        assertEquals("关键词8", recent.first())
        assertEquals(1, recent.count { it == "关键词8" })
        assertEquals(old, updatedSearchHistory(old, "  "))
        assertFalse(updatedSearchHistory(old, "新关键词").contains("关键词16"))
        assertEquals(listOf("JAY"), updatedSearchHistory(listOf("jay"), "JAY"))
    }

    @Test fun menuFitsSmallPhoneHalfScreenAndTallKeyboard() {
        assertEquals(360f, searchMenuHeight(720f, 280f, 10f, 360f), .01f)
        assertEquals(178f, searchMenuHeight(720f, 520f, 10f, 360f), .01f)
        assertEquals(160f, searchMenuHeight(320f, 0f, 10f, 204f), .01f)
        assertEquals(0f, searchMenuHeight(200f, 220f, 10f, 204f), .01f)
        assertEquals(204f, searchMenuHeight(1000f, 300f, 10f, 204f), .01f)
    }

    @Test fun paginationKeepsIdentityOrderAndNewPageMetadata() {
        val first = QqSearchPage(singers = listOf(QqSearchSinger("a", "歌手甲", null)), page = 1, hasMore = true)
        val next = QqSearchPage(singers = listOf(QqSearchSinger("a", "歌手甲", null), QqSearchSinger("b", "歌手乙", null)), page = 2)
        val merged = first.append(next)
        assertEquals(listOf("a", "b"), merged.singers.map { it.id })
        assertEquals(2, merged.page)
        assertFalse(merged.hasMore)
        assertFalse(merged.empty)
    }

    @Test fun albumDoesNotTriggerPlaylistLibraryRefresh() {
        val album = MusicPlaylist("qq-album-abc", MusicSource.QQ, "专辑", "歌手", "", 0, 0, 0, "专", emptyList())
        assertTrue(album.isQqSearchAlbum)
        assertFalse(shouldSyncQqLibraryAfterPlaylistReturn(album))
        assertTrue(shouldSyncQqLibraryAfterPlaylistReturn(album.copy(id = "qq-123")))
    }
}
