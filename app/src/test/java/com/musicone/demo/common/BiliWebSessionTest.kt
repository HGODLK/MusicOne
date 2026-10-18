package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class BiliWebSessionTest {
    @Test fun accountCredentialsDoNotRemoveDeviceIdentityOrTruncateEncodedValues() {
        val merged = parseBiliCookies(mergeBiliCookies("buvid3=device; buvid4=secondary", "SESSDATA=abc%2Fdef==; bili_jct=token"))
        assertEquals("device", merged["buvid3"])
        assertEquals("secondary", merged["buvid4"])
        assertEquals("abc%2Fdef==", merged["SESSDATA"])
        assertEquals("token", merged["bili_jct"])
    }

    @Test fun loginDeviceCookieTakesPrecedenceAndEmptyCredentialKeepsDevice() {
        assertEquals("login", parseBiliCookies(mergeBiliCookies("buvid3=device", "buvid3=login"))["buvid3"])
        assertEquals(mapOf("buvid3" to "device"), parseBiliCookies(mergeBiliCookies("buvid3=device", "")))
    }
}
