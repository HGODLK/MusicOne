package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqCredentialContextTest {
    @Test
    fun 混合身份时播放和认证都使用音乐账号() {
        val credential = "wxuin=998877; uin=o0000776655; musicid=00123456; musickey=private-ticket"
        val comm = qqPlaybackComm(credential)
        assertEquals("123456", qqCredentialAccountId(credential))
        assertEquals("123456", comm.optString("qq"))
        assertEquals("123456", qqPersonalizedAccountId(credential))
    }

    @Test
    fun 只含字符串音乐账号时也能取得有效身份() {
        assertEquals("123456", qqCredentialAccountId("str_musicid=123456; musickey=private-ticket"))
        assertEquals("123456", qqPlaybackComm("str_musicid=123456").optString("qq"))
    }

    @Test
    fun 空或零音乐账号不会遮盖有效的旧凭据() {
        assertEquals("123456", qqCredentialAccountId("str_musicid=0; musicid=; qqmusic_uin=null; uin=o0000123456"))
        assertEquals("123456", qqCredentialAccountId("wxuin=0000123456"))
        assertEquals("", qqCredentialAccountId("str_musicid=0; uin=invalid"))
    }

    @Test
    fun 请求上下文诊断保留一致性但不泄露标识和凭据() {
        val credential = "musicid=123456; wxuin=998877; musickey=private-ticket"
        val params = JSONObject().put("uin", "123456").put("platform", "23")
            .put("filename", JSONArray().put("M500private-media.mp3"))
        val body = JSONObject().put("comm", qqPlaybackComm(credential))
            .put("req_1", JSONObject().put("param", params))
        val diagnostic = qqTicketContextDiagnostic(body, credential)
        assertTrue(diagnostic.contains("账号字段=musicid"))
        assertTrue(diagnostic.contains("认证与换票账号一致=true"))
        listOf("123456", "998877", "private-ticket", "private-media").forEach {
            assertFalse(diagnostic.contains(it))
        }
        params.put("uin", "998877")
        assertTrue(qqTicketContextDiagnostic(body, credential).contains("认证与换票账号一致=false"))
    }
}
