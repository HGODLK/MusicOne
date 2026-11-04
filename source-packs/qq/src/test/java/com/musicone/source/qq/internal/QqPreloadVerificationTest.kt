package com.musicone.source.qq.internal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqPreloadVerificationTest {
    @Test fun preloadVerificationDoesNotPauseAndNextPlaybackRequestsServerAgain() = runBlocking {
        val cookie = "uin=9876543210"
        QqSessionRequestCoordinator.sessionChanged(cookie)
        try {
            val response = JSONObject().put("code", 2001)
                .put("feedbackURL", "https://support.qq.com/verify")
            val failure = runCatching {
                withTimeout(1_000) {
                    retryQqRequestAfterVerification(cookie, interactive = false) {
                        requireQqMusicUSuccess(response, cookie, QqRequestOrigin.PRELOAD)
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is PlatformSecurityVerificationRequired)
            assertNull(QqSessionRequestCoordinator.verification.value)
            QqSessionRequestCoordinator.beforeTicketRequest(cookie)
            assertNull(QqSessionRequestCoordinator.verification.value)
            var requests = 0
            val result = retryQqRequestAfterVerification(cookie) {
                QqSessionRequestCoordinator.beforeTicketRequest(it)
                requests++
                "服务器已恢复"
            }
            assertEquals(1, requests)
            assertEquals("服务器已恢复", result)
            assertNull(QqSessionRequestCoordinator.verification.value)
        } finally {
            QqSessionRequestCoordinator.cancel()
        }
    }
}
