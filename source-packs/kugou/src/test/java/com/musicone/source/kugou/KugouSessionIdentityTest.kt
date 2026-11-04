package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test

class KugouSessionIdentityTest {
    @Test fun profileRefreshKeepsStartupRequestsAndPendingLoginCurrent() {
        assertFalse(kugouSessionChanged("cookie-a", "user-a", "cookie-a", "user-a", null))
    }
    @Test fun cookieUserAndExplicitLoginChangesInvalidateOldRequests() {
        assertTrue(kugouSessionChanged("cookie-a", "user-a", "cookie-b", "user-a", null))
        assertTrue(kugouSessionChanged("cookie-a", "user-a", "cookie-a", "user-b", null))
        assertTrue(kugouSessionChanged("cookie-a", "user-a", "cookie-a", "user-a", 3L))
        assertTrue(kugouSessionChanged("", null, "cookie-a", "user-a", null))
    }
}
