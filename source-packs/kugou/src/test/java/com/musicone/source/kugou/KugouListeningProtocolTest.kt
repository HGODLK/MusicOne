package com.musicone.source.kugou

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouListeningProtocolTest {
    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=mid; KUGOU_API_GUID=0123456789abcdef0123456789abcdef; dfid=device"

    @Test fun gradeQueryAndReportUseExistingLiteIdentityWithoutInventedMembership() {
        val query = kugouGradeRequest(cookie, 1_234_000, null, null)
        val first = JSONObject(query.body)
        assertEquals("userinfo.user.kugou.com", URI.create(query.url).host)
        assertEquals("/v2/get_grade_info", URI.create(query.url).path)
        assertFalse(first.has("d_sec")); assertFalse(first.has("diff_sec"))
        assertEquals("3116", first.getString("appid"))
        assertEquals(KugouProtocol.clientVersion, first.getString("clientver"))
        assertEquals(KugouProtocol.loginKey(1_234), first.getString("key"))
        val report = JSONObject(kugouGradeRequest(cookie, 1_234_000, 20_000, 17).body)
        assertEquals(20_000L, report.getLong("d_sec")); assertEquals(17L, report.getLong("diff_sec"))
        assertEquals(0, report.getInt("y_type")); assertEquals(0, report.getInt("m_type"))
        assertFalse(report.has("token")); assertFalse(report.has("user_type"))
        assertFalse(report.toString().contains("test-token"))
        assertEquals(256, report.getString("p").length)
    }

    @Test fun gradeSuccessMustContainActualNonnegativeServerDuration() = runBlocking {
        for (response in listOf(JSONObject(), JSONObject().put("status", 0),
            JSONObject().put("status", 1).put("data", JSONObject()),
            JSONObject().put("status", 1).put("data", JSONObject().put("d_sec", -1)))) {
            val client = KugouListeningClient(KugouListeningCscc({ error("不应调用CSCC") }), sendGrade = { _, _ -> response })
            try { client.baseline(cookie) {}; fail("不应接受缺失时长或失败状态") } catch (_: IllegalArgumentException) { }
        }
        val client = KugouListeningClient(KugouListeningCscc({ error("不应调用CSCC") }), sendGrade = { _, _ ->
            JSONObject().put("status", 1).put("data", JSONObject().put("d_sec", 9_000)) })
        assertEquals(9_000L, client.baseline(cookie) {})
    }

    @Test fun csccBinaryRoundTripKeepsRealMillisecondsAndUsesInterruptedState() {
        val uuid = "0123456789abcdef0123456789abcdef"
        val session = KugouCsccSession("remote-cookie", "1234", "server", "0123456789abcdef0123456789abcdef")
        val device = KugouListeningDevice("Actual Phone", "15", 1080, 2400, "192.168.1.8")
        val request = kugouCsccPlaybackRequest(session, cookie.requiredKugouSession(), uuid, device,
            77, false, 12_345, false, 9_876_000, 9_877_000)
        val params = URI.create(request.url).rawQuery.split('&').associate { field ->
            val pair = field.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        val key = KugouProtocol.md5(uuid + session.clienttime + session.field2 + session.serverstr)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(uuid.take(16).toByteArray()))
        }
        val plain = inflate(cipher.doFinal(request.body!!))
        assertEquals(plain.size.toString(), params["length"])
        val separator = "\r\n".toByteArray()
        var offset = 0
        repeat(2) {
            while (!(plain[offset] == separator[0] && plain[offset + 1] == separator[1])) offset++
            offset += 2
        }
        val fields = inflate(plain.copyOfRange(offset, plain.size)).toString(Charsets.UTF_8)
            .split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("12345", fields["duration"])
        assertEquals("中断播放", fields["state"])
        assertEquals("Actual Phone", fields["mod"])
        assertEquals("9876000", fields["time"])
        assertFalse(fields.containsKey("fo")); assertFalse(fields.containsKey("fo3"))
        val prefix = params.filterKeys { it != "sign" }.toSortedMap().entries.joinToString("") { it.key + it.value }
        val digest = MessageDigest.getInstance("MD5").digest((prefix + "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA").toByteArray() + request.body!!)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, params["sign"])
    }

    private fun inflate(bytes: ByteArray) = InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
}
