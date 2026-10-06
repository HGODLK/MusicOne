package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPersonalizedMappersTest {
    @Test
    fun stableGuidIsDeterministicAndNumeric() {
        val first = qqStableGuid("device-123")

        assertEquals(first, qqStableGuid("device-123"))
        assertEquals(10, first.length)
        assertTrue(first.all(Char::isDigit))
    }

    @Test
    fun personalizedRadioCommUsesLoggedInAccountAndAuthTicket() {
        val values = qqPersonalizedCommValues(
            "wxuin=999999; musicid=00123456; musickey=account-ticket",
        )

        assertEquals("123456", values["uin"])
        assertEquals("account-ticket", values["authst"])
        assertEquals("json", values["format"])
        assertEquals(19, values["ct"])
        assertEquals(0, values["cv"])
    }
}
