package com.musicone.source.qq.internal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

class QqListenDurationTest {
    private val session = PlatformSession(MusicSource.QQ, "musicid=123456; musickey=real-music-key; loginType=2", "stable-device", null)
    private val device = QqDurationDevice("15", "真实设备 & 平板")
    private val track = MusicTrack("qq-00123456789012", MusicSource.QQ, "歌曲", "歌手 & 合唱", "专辑",
        180_000L, 0, 0, "歌", "", catalogId = "789")

    @Test fun cumulativeEventsOnlySubmitTheNewlyListenedPart() {
        val ledger = QqListenDurationLedger()
        assertTrue(ledger.update(1, 0))
        assertTrue(ledger.update(2, 12_345))
        assertEquals(12_000L, ledger.pendingMs())
        ledger.markAttempted(ledger.pendingMs())
        assertTrue(ledger.update(3, 12_345))
        assertEquals(0L, ledger.pendingMs())
        assertTrue(ledger.update(4, 20_120))
        assertEquals(8_000L, ledger.pendingMs())
    }

    @Test fun subsecondRemaindersSurvivePauseAndResume() {
        val ledger = QqListenDurationLedger()
        ledger.update(1, 999)
        assertEquals(0L, ledger.pendingMs())
        ledger.update(2, 1_500)
        ledger.markAttempted(1_000)
        ledger.update(3, 2_001)
        assertEquals(1_000L, ledger.pendingMs())
    }

    @Test fun duplicateReorderedAndRegressingCountersCannotChargeTwice() {
        val ledger = QqListenDurationLedger()
        ledger.update(10, 5_000)
        ledger.markAttempted(5_000)
        assertFalse(ledger.update(10, 7_000))
        assertFalse(ledger.update(9, 9_000))
        assertFalse(ledger.update(11, 4_000))
        assertEquals(0L, ledger.pendingMs())
        assertTrue(ledger.update(12, 6_000))
        assertEquals(1_000L, ledger.pendingMs())
    }

    @Test fun lostAcknowledgementDoesNotResendTheAttemptedSegment() {
        val ledger = QqListenDurationLedger()
        ledger.update(1, 8_000)
        ledger.markAttempted(8_000)
        ledger.update(2, 8_000)
        assertEquals(0L, ledger.pendingMs())
        ledger.update(3, 9_000)
        assertEquals(1_000L, ledger.pendingMs())
    }

    @Test fun renewedCredentialsKeepTheAccountButReloginAndSwitchInvalidateIt() {
        assertTrue(qqListenSessionAccountMatches("123456", 4, session.copy(credential = "musicid=123456; musickey=new-key"), 4))
        assertFalse(qqListenSessionAccountMatches("123456", 4, session, 5))
        assertFalse(qqListenSessionAccountMatches("123456", 4, session.copy(credential = "musicid=654321; musickey=other-key"), 4))
        assertFalse(qqListenSessionAccountMatches("123456", 4, session.copy(credential = ""), 4))
    }

    @Test fun xmlCarriesRealIdentityTicketAndDeltaWithoutInventingVipOrEnvironment() {
        val xml = qqListenDurationXml(session, device, "device-uid", "device-sid", track, "real-vkey&token", 60_000, 1_700_000_000_000)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        val item = root.getElementsByTagName("item").item(0).attributes
        assertEquals("123456", item.getNamedItem("QQ").nodeValue)
        assertEquals("789", item.getNamedItem("songid").nodeValue)
        assertEquals("60", item.getNamedItem("time").nodeValue)
        assertEquals("60000", item.getNamedItem("play_duration_mi").nodeValue)
        assertEquals("180000", item.getNamedItem("audiotime").nodeValue)
        assertEquals("9318BF5A5E76BB6D2A755E9DCCAD7202", item.getNamedItem("timekey").nodeValue)
        assertEquals("real-vkey&token", item.getNamedItem("vkey").nodeValue)
        assertEquals(device.model, item.getNamedItem("model").nodeValue)
        assertNull(item.getNamedItem("vip_level"))
        assertNull(item.getNamedItem("nettype"))
        assertNull(item.getNamedItem("string27"))
        assertEquals("real-music-key", root.getElementsByTagName("authst").item(0).textContent)
        assertEquals("device-sid", root.getElementsByTagName("sid").item(0).textContent)
    }

    @Test fun gzipAndEncodedVkeyPreserveTheirContents() {
        assertEquals("a+b&c", qqListenVkey("https://dl.stream.qqmusic.qq.com/test.mp3?vkey=a%2Bb%26c&guid=1"))
        assertEquals("", qqListenVkey("https://example.com/test.mp3"))
        val xml = qqListenDurationXml(session, device, "uid", "sid", track, "vkey", 1_000, 1_000)
        assertEquals(xml, GZIPInputStream(qqGzipXml(xml).inputStream()).use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test fun androidSessionUsesAnonymousFirstHandshakeThenExistingUidForDailyRefresh() {
        val first = qqDurationSessionRequest(session.deviceId, device, "")
        val request = first.getJSONObject("req_0")
        assertEquals("music.getSession.session", request.getString("module"))
        assertEquals("GetSession", request.getString("method"))
        assertEquals(2, request.getJSONObject("param").getInt("caller"))
        assertEquals(0, request.getJSONObject("param").getInt("vkey"))
        assertFalse(first.getJSONObject("comm").has("authst"))
        val refresh = qqDurationSessionRequest(session.deviceId, device, "previous-uid").getJSONObject("req_0").getJSONObject("param")
        assertEquals("previous-uid", refresh.getString("uid"))
        assertEquals(1, refresh.getInt("caller"))
        val response = JSONObject("""{"code":0,"req_0":{"code":0,"data":{"session":{"uid":123,"sid":"signed-session"}}}}""")
        assertEquals("123" to "signed-session", qqDurationSessionResponse(response))
    }

    @Test fun gatewayRequiresAConfirmedSuccessCodeAfterItsFiveByteZlibHeader() {
        val compressed = ByteArrayOutputStream().also { bytes ->
            bytes.write(ByteArray(5))
            DeflaterOutputStream(bytes).use { it.write("{\"code\":0}".toByteArray()) }
        }.toByteArray()
        requireQqListenDurationSuccess(PlatformHttpResponse(200, compressed, emptyMap()))
        assertThrows(PlatformApiException::class.java) {
            requireQqListenDurationSuccess(PlatformHttpResponse(200, "{\"code\":1}".toByteArray(), emptyMap()))
        }
        assertThrows(PlatformApiException::class.java) {
            requireQqListenDurationSuccess(PlatformHttpResponse(200, ByteArray(0), emptyMap()))
        }
    }

    @Test fun invalidNumericSongIdAndMissingSessionNeverCountAsSuccess() {
        assertThrows(PlatformApiException::class.java) {
            qqListenDurationXml(session, device, "uid", "sid", track.copy(catalogId = "song-mid"), "vkey", 1_000, 1_000)
        }
        assertThrows(PlatformApiException::class.java) {
            qqDurationSessionResponse(JSONObject("""{"code":0,"req_0":{"code":0,"data":{"session":{"uid":"uid"}}}}"""))
        }
    }
}
