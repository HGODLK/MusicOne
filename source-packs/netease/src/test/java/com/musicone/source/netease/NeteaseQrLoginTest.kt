package com.musicone.source.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseQrLoginTest {
    @Test fun qrResponseCodesMapToLoginStates() {
        assertEquals(NeteaseQrLoginStatus.EXPIRED, qrLoginStatus(800))
        assertEquals(NeteaseQrLoginStatus.WAITING_SCAN, qrLoginStatus(801))
        assertEquals(NeteaseQrLoginStatus.WAITING_CONFIRM, qrLoginStatus(802))
        assertEquals(NeteaseQrLoginStatus.SUCCESS, qrLoginStatus(803))
        assertNull(qrLoginStatus(500))
    }
}
