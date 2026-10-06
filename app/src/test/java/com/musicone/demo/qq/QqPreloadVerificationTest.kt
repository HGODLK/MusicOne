package com.musicone.demo

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqPreloadVerificationTest {
    @Test fun preloadVerificationDoesNotOpenDialogOrWaitAndPlaybackStillRequiresIt() = runBlocking {
        val cookie = "uin=9876543210"
        QqSessionRequestCoordinator.cancel()
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
            assertTrue(runCatching {
                QqSessionRequestCoordinator.beforeTicketRequest(cookie, showVerification = false)
            }.exceptionOrNull() is PlatformSecurityVerificationRequired)
            assertNull(QqSessionRequestCoordinator.verification.value)
            assertTrue(runCatching {
                QqSessionRequestCoordinator.beforeTicketRequest(cookie)
            }.exceptionOrNull() is PlatformSecurityVerificationRequired)
            assertNotNull(QqSessionRequestCoordinator.verification.value)
        } finally {
            QqSessionRequestCoordinator.cancel()
        }
    }
}
