package com.musicone.source.netease

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteasePlaybackResponseTest {
    @Test fun emptyPlaybackUrlDoesNotProvePermissionOrResourceFailure() {
        try {
            neteasePlaybackSource(JSONObject().put("code", 200), NeteaseQuality.STANDARD)
            fail("空地址应报告可重试的取址失败")
        } catch (error: NeteaseProtocolException) {
            assertEquals("歌曲暂无可用播放地址", error.message)
        }
    }

}
