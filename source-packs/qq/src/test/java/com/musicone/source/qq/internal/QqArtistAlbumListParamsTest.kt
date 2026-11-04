package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class QqArtistAlbumListParamsTest {
    @Test fun albumsUsePublishTimeOrder() {
        // 官方 order=0 为发行时间倒序，order=1 为热度序；歌手页专辑默认必须按时间。
        val params = qqArtistAlbumListParams("0025NhlN2yWrP4", 50)
        assertEquals(0, params.getInt("order"))
        assertEquals("0025NhlN2yWrP4", params.getString("singerMid"))
        assertEquals(50, params.getInt("num"))
        assertEquals(50, params.getInt("begin"))
    }
}
