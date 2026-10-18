package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouFavoriteHashTest {
    // 收藏项编号缺失（原先会整条丢弃，导致红心消失）不能影响回读判定。
    @Test fun missingFavoriteIdDoesNotDropTheEntry() {
        assertEquals("ABCDEF", JSONObject("""{"FileHash":"ABCDEF"}""").kugouFavoriteHash())
        assertEquals("ABCDEF", JSONObject("""{"FileHash":"ABCDEF","ID":999}""").kugouFavoriteHash())
    }

    // 只返回高阶档位字段时仍要能判定，不能只认 FileHash。
    @Test fun fallsBackToOtherQualityHashes() {
        assertEquals("hq", JSONObject("""{"HQFileHash":"hq"}""").kugouFavoriteHash())
        assertEquals("sq", JSONObject("""{"SQFileHash":"sq"}""").kugouFavoriteHash())
        assertEquals("legacy", JSONObject("""{"hash":"legacy"}""").kugouFavoriteHash())
    }

    @Test fun blanksAreIgnored() {
        assertNull(JSONObject("""{"FileHash":" ","HQFileHash":"null","SQFileHash":""}""").kugouFavoriteHash())
    }
}
