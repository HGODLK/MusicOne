package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaylistDescriptionTest {
    @Test fun qqPlaylistDescriptionDecodesEmojiEntitiesAndHtmlBreaks() {
        assertEquals(
            "🎼 和你一起\n星河 & 晚风",
            decodeQqPlaylistDescription("&#127932;&#160;和你一起<br>星河 &amp; 晚风"),
        )
        assertEquals("~ 纯音 ❀ galgame", decodeQqPlaylistDescription("~ 纯音 &#10048; galgame"))
    }
}
