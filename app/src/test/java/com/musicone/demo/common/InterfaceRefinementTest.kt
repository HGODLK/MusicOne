package com.musicone.demo

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InterfaceRefinementTest {
    @Test fun libraryUsesPhoneAndTabletColumns() {
        assertEquals(2, qqLibraryColumns(375.dp))
        assertEquals(3, qqLibraryColumns(800.dp))
        assertEquals(4, qqLibraryColumns(1280.dp))
    }
    @Test fun artistAvatarIsNotReplacedByBackground() {
        val header = JSONObject("""{"BaseInfo":{"Avatar":"https://test/avatar.jpg","BackgroundImage":"https://test/bg.jpg"},
            "Singer":{"SingerImageLists":[{"ImageList":[{"HighResolutionImage":"https://test/bg2.jpg"}]}],
            "phone_singer_portrait_list":["https://test/bg.jpg","invalid"]}}""")
        val profile = artistProfileFromHeader(header, QqSearchSinger("id", "歌手", null), "简介")
        assertEquals("https://test/avatar.jpg", profile.artwork)
        assertEquals(listOf("https://test/bg.jpg", "https://test/bg2.jpg"), profile.backgrounds)
    }
    @Test fun missingGalleryDoesNotInventImages() {
        val profile = artistProfileFromHeader(null, QqSearchSinger("id", "歌手", "https://test/avatar.jpg"), "")
        assertTrue(profile.backgrounds.isEmpty())
        assertEquals("https://test/avatar.jpg", profile.artwork)
    }
    @Test fun galleryUsesOfficialPortraitMidAndSkipsBlockedPictures() {
        val header = JSONObject("""{"Singer":{"SingerPMid":"singer_11","SingerImageLists":[{"type":1,
            "ImageList":[{"ImageMid":"photoA","HighResolutionImage":""},
            {"ImageMid":"photoB","BlockCarousel":1},{"ImageMid":"photoA"}]}]}}""")
        val profile = artistProfileFromHeader(header, QqSearchSinger("singer", "歌手", null), "")
        assertEquals(listOf("https://y.gtimg.cn/music/photo_new/T065R1080x1920M000photoA.jpg",
            "https://y.gtimg.cn/music/photo_new/T001R800x800M000singer_11.jpg"), profile.backgrounds)
        assertNull(profile.artwork)
    }
    @Test fun entryAvatarIsStableWhenHeaderOffersAnotherVersion() {
        val header = JSONObject("""{"BaseInfo":{"Avatar":"https://test/new_avatar.jpg"}}""")
        val profile = artistProfileFromHeader(header, QqSearchSinger("id", "歌手", "https://test/entry.jpg"), "简介")
        assertEquals("https://test/entry.jpg", profile.artwork)
        assertTrue(profile.backgrounds.isEmpty())
    }
    @Test fun artistHeaderKeepsNumericSingerIdentityForScopedSearch() {
        val header = JSONObject("""{"Singer":{"SingerID":4558,"Name":"歌手"}}""")
        val profile = artistProfileFromHeader(header, QqSearchSinger("mid", "歌手", null), "")
        assertEquals(4558L, profile.singerId)
        assertEquals("song", profile.songTabId)
    }
    @Test fun biographyPreservesFullDescriptionAndRepeatedMemberFields() {
        val text = artistBiography("""<result><code>0</code><data><info><desc><![CDATA[完整简介，保留最后一句。]]></desc>
            <basic><item><key>国籍</key><value>中国</value></item></basic>
            <group><member><item><key>中文名</key><value>成员甲</value></item></member>
            <member><item><key>中文名</key><value>成员乙</value></item></member></group></info></data></result>""")
        assertTrue(text.contains("保留最后一句。"))
        assertTrue(text.contains("基本资料\n\n国籍：中国"))
        assertTrue(text.contains("中文名：成员甲\n\n中文名：成员乙"))
    }
    @Test fun inkChoosesContrastAndIgnoresOneBrightOutlier() {
        assertTrue(miniPlayerRegionUsesLightInk(List(24) { .07f }, false))
        assertFalse(miniPlayerRegionUsesLightInk(List(24) { .8f }, true))
        assertTrue(miniPlayerRegionUsesLightInk(List(23) { .07f } + .95f, false))
    }
    @Test fun inkRetainsPreviousChoiceNearThreshold() {
        val samples = List(24) { .179f }
        assertTrue(miniPlayerRegionUsesLightInk(samples, true))
        assertFalse(miniPlayerRegionUsesLightInk(samples, false))
    }
    @Test fun pageTouchAndSettleGateColorSampling() {
        val activity = PageInteractionActivity()
        activity.lastTouchEvent = 1000L
        assertFalse(activity.quiet(1030L))
        assertTrue(activity.quiet(1060L))
        activity.pressed = true
        assertFalse(activity.quiet(5000L))
    }
    @Test fun pageFlingKeepsSamplingPausedAfterTouchSettles() {
        val activity = PageInteractionActivity()
        val grid = Any()
        var scrolling = true
        var visible = true
        activity.lastTouchEvent = 1000L
        activity.bindScroll(grid) { visible && scrolling }
        assertFalse(activity.quiet(5000L))
        scrolling = false
        assertTrue(activity.quiet(5000L))
        scrolling = true
        visible = false
        assertTrue(activity.quiet(5000L))
        visible = true
        assertFalse(activity.quiet(5000L))
        activity.unbindScroll(grid)
        assertTrue(activity.quiet(5000L))
    }
    @Test fun removingOnePageDoesNotResumeSamplingWhileAnotherScrolls() {
        val activity = PageInteractionActivity()
        val first = Any()
        val second = Any()
        activity.bindScroll(first) { true }
        activity.bindScroll(second) { true }
        activity.unbindScroll(first)
        assertFalse(activity.quiet(5000L))
        activity.unbindScroll(second)
        assertTrue(activity.quiet(5000L))
    }
}
