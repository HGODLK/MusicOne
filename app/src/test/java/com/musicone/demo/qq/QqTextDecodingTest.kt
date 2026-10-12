package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QqTextDecodingTest {
    @Test fun plainNamesKeepInternalWhitespaceAndTrimEdges() {
        assertEquals("Inner  Light\t现场版", decodeQqPlaylistDescription("  Inner  Light\t现场版\r "))
        assertEquals("", decodeQqPlaylistDescription(" \t\r "))
    }

    @Test fun htmlParagraphsAndBreaksKeepTheirLineBoundaries() {
        assertEquals("第一行\n\n第二行\n第三行", decodeQqPlaylistDescription(
            "<p>第一行</P ><BR />第二行<br>第三行</p>",
        ))
    }

    @Test fun entitiesDecodeAtMostTwoRoundsAndKeepUnknownValues() {
        assertEquals("🎼 和你 & \" ' <b>曲名</b> &bogus; &#1114112;", decodeQqPlaylistDescription(
            "&#x1F3BC;&nbsp;和你 &amp; &quot; &#39; &amp;lt;b&amp;gt;曲名&amp;lt;/b&amp;gt; &bogus; &#1114112;",
        ))
        assertEquals("&amp;", decodeQqPlaylistDescription("&amp;amp;amp;"))
    }

    @Test fun lineCleanupPreservesTwoLineBreaksAndNormalizesNonBreakingSpace() {
        assertEquals("甲\n\n乙\n丙 丁", decodeQqPlaylistDescription("甲 \t\n\n\n\n乙 \t\n丙\u00A0丁"))
    }

    @Test fun searchTextStripsDecodedTagsAndKeepsFieldFallbackRules() {
        val value = JSONObject().put("blank", " ").put("missing", "null")
            .put("title", "&lt;b&gt;曲名&lt;/b&gt;<>&amp;")
        assertEquals("曲名&", value.searchText("blank", "missing", "title"))
        assertEquals("", value.searchText("absent"))
        assertEquals("歌手甲", JSONObject().put("name", "歌手甲").searchText("mid", "name"))
    }
}
