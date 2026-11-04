package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostArtistPresentationTest {
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
}
