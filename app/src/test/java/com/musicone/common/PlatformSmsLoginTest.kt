package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformSmsLoginTest {
    @Test
    fun securityVerificationKeepsSessionAndReplacesUpdatedCookies() {
        assertEquals(
            "device=one; session=new; verify=passed",
            mergePlatformCredentials(
                "device=one; session=old",
                "verify=passed; session=new",
            ),
        )
    }

}
