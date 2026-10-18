package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouPrivilegeQualitiesTest {
    @Test fun keyedQualityObjectsWithoutQualityFieldsRemainPlayable() {
        val response = JSONObject("""{"data":{
            "128":{"url":"https://cdn.kugou.com/base.mp3"},
            "320":{"info":{"backup_url":["https://cdn.kugou.com/high.mp3"]}},
            "flac":{"url":"https://cdn.kugou.com/lossless.flac"},
            "high":{"status":0,"url":"https://cdn.kugou.com/hires.flac"}
        }}""")

        assertEquals("flac", response.requireKugouPrivilegeItem(AudioQuality.LOSSLESS).optString("quality"))
        assertEquals(listOf("STANDARD", "EXHIGH", "LOSSLESS"), response.kugouPlayableQualityNames())
        assertNull(response.optJSONObject("data")!!.optJSONObject("flac")!!.opt("quality"))
    }

    @Test fun emptyMatchingRowDoesNotHidePlayableBackupRow() {
        val response = JSONObject("""{"data":{"quality":"flac","relate_goods":[
            {"quality":"flac","info":{"backup_url":["https://cdn.kugou.com/full.flac"]}}
        ]}}""")

        val item = response.requireKugouPrivilegeItem(AudioQuality.LOSSLESS)
        assertEquals("https://cdn.kugou.com/full.flac", item.optJSONObject("info")!!.pickKugouUrl())
    }

    @Test fun mapsPrivilegeLevelsAndReportsOnlyPlayableTiers() {
        val response = JSONObject("""{"data":{"quality":"128","url":"https://cdn.kugou.com/base.mp3",
            "relate_goods":[
                {"level":4,"info":{"url":"https://cdn.kugou.com/high.mp3"}},
                {"level":5,"info":{"url":"https://cdn.kugou.com/lossless.flac"}},
                {"level":6,"info":{"url":"https://cdn.kugou.com/hires.flac"}}
            ]}}""")

        assertEquals(5, response.kugouPrivilegeItem("flac")?.optInt("level"))
        assertEquals(listOf("STANDARD", "EXHIGH", "LOSSLESS", "HI_RES"), response.kugouPlayableQualityNames())
    }

    @Test fun privilegeRowsWithoutAPlayableAddressAreNotReported() {
        val response = JSONObject("""{"data":{"quality":"128","url":"https://cdn.kugou.com/base.mp3",
            "relate_goods":[{"level":5,"status":0,"info":{"url":"https://cdn.kugou.com/lossless.flac"}}]}}""")

        assertNull(response.kugouPrivilegeItem("flac"))
        assertEquals(listOf("STANDARD"), response.kugouPlayableQualityNames())
    }

    @Test fun missingTierIsDistinctWhenAnotherTierHasAPlayableUrl() {
        val response = JSONObject("""{"data":{"quality":"128","url":"https://cdn.kugou.com/base.mp3"}}""")

        val error = runCatching { response.requireKugouPrivilegeItem(AudioQuality.LOSSLESS) }.exceptionOrNull()
        assertTrue(error is KugouQualityUnavailable)
    }
}
