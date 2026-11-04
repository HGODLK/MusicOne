package com.musicone

import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceTrackMetadataRoundTripTest {
    private val artist = SourceEntityRef("artist", "歌手", "artist", SourcePayload("private.artist", 3, "不透明\n定位资料"))
    private val album = SourceEntityRef("album", "专辑", "album", SourcePayload("private.album", 7, "{\"key\":42}"))
    private val qualities = listOf(
        SourceAudioQuality("LOSSLESS", "无损", "audio/flac", true, payload = SourcePayload("private.quality", 2, "票据定位")),
        SourceAudioQuality("custom-tier", "私有档位", null, false, "需授权", SourcePayload("other", 4, "opaque")),
        SourceAudioQuality("EXHIGH", "高品质", reason = ""),
    )
    private fun details() = SourceTrackDetails(
        SourceTrack(TrackIdentity("org.example.roundtrip", "account", "remote"), "歌曲", listOf("歌手"),
            opaqueData = mapOf("qualityIds" to "{\"STANDARD\":\"legacy-hash\"}", "custom" to "原始值")),
        "stable", album = "专辑", artists = listOf(artist), albumRef = album, qualities = qualities,
        payload = SourcePayload("private.track", 9, "曲目载荷"),
    )
    private fun reload(track: MusicTrack) = JSONObject(track.toStoredTrackJson().toString()).toStoredTrack()!!

    @Test fun completeMetadataSurvivesRepeatedStorageAndCallbackRoundTrips() {
        val original = details()
        var restored = original
        repeat(3) {
            val stored = reload(restored.toMusicTrack())
            assertEquals(artist, stored.artistRefs.single().sourceReference())
            assertEquals(album.payload, stored.relatedAlbum()!!.templateEntityReference()!!.payload)
            restored = stored.toSourceTrack()
            assertEquals(original.artists, restored.artists)
            assertEquals(original.albumRef, restored.albumRef)
            assertEquals(original.qualities, restored.qualities)
            assertEquals(original.payload, restored.payload)
            assertEquals(original.track.identity, restored.track.identity)
            assertEquals(original.stableId, restored.stableId)
            assertEquals("原始值", restored.track.opaqueData["custom"])
            assertEquals("legacy-hash", stored.qualityIds[AudioQuality.STANDARD])
        }
    }

    @Test fun absentLegacyMetadataFallsBackWithoutInventingProviderPayload() {
        val json = details().toMusicTrack().toStoredTrackJson().apply {
            remove("audioQualities"); remove("albumReference")
            getJSONArray("artistRefs").getJSONObject(0).remove("reference")
        }
        val stored = JSONObject(json.toString()).toStoredTrack()!!
        val restored = stored.toSourceTrack()
        assertEquals(SourceEntityRef("artist", "歌手", "artist"), restored.artists.single())
        assertEquals(SourceEntityRef("album", "专辑", "album"), restored.albumRef)
        assertEquals(listOf("STANDARD"), restored.qualities.map { it.id })
        assertEquals("stable", restored.stableId)
    }

    @Test fun explicitEmptyQualityListAndUserEditsSurviveStorage() {
        val original = details().copy(qualities = emptyList()).toMusicTrack()
        val restored = reload(original.copy(title = "用户标题", artists = "用户歌手", album = "用户专辑")).toSourceTrack()
        assertTrue(restored.qualities.isEmpty())
        assertEquals("用户标题", restored.track.title)
        assertEquals("用户歌手", restored.artistsText)
        assertEquals("用户专辑", restored.album)
        assertEquals(artist.payload, restored.artists.single().payload)
        assertEquals(album.payload, restored.albumRef!!.payload)
    }

    @Test fun packEchoingOpaqueDataIntoPayloadDoesNotGrowAcrossRoundTrips() {
        // 模拟把整份 opaqueData 重新编码进自有载荷的音源，往返多次后载荷长度保持不变。
        fun echo(sent: SourceTrackDetails) = sent.copy(payload = SourcePayload("private.track", 1,
            JSONObject(sent.track.opaqueData).toString()))
        var track = details().toMusicTrack()
        val lengths = (0 until 6).map {
            track = reload(echo(track.toSourceTrack()).toMusicTrack())
            assertFalse(track.toSourceTrack().track.opaqueData.keys.any { it in HOST_SOURCE_PAYLOAD_KEYS })
            track.providerData["sourcePayload"].orEmpty().length
        }
        assertEquals(1, lengths.distinct().size)
    }

    @Test fun storedNestedHostPayloadIsStrippedOnLoad() {
        val nested = JSONObject().put("own", "值").put("sourcePayload", "x".repeat(1000))
            .put("sourcePayloadFormat", "private.track").toString()
        val stored = details().toMusicTrack().toStoredTrackJson()
        stored.getJSONObject("providerData").put("sourcePayload", nested)
        val restored = JSONObject(stored.toString()).toStoredTrack()!!
        val payload = JSONObject(restored.providerData.getValue("sourcePayload"))
        assertEquals("值", payload.getString("own"))
        assertFalse(payload.has("sourcePayload") || payload.has("sourcePayloadFormat"))
    }
}
