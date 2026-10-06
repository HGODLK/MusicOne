package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseQrLoginTest {
    @Test fun qrResponseCodesMapToLoginStates() {
        assertEquals(PlatformQrLoginStatus.EXPIRED, qrLoginStatus(800))
        assertEquals(PlatformQrLoginStatus.WAITING_SCAN, qrLoginStatus(801))
        assertEquals(PlatformQrLoginStatus.WAITING_CONFIRM, qrLoginStatus(802))
        assertEquals(PlatformQrLoginStatus.SUCCESS, qrLoginStatus(803))
        assertNull(qrLoginStatus(500))
    }
}
