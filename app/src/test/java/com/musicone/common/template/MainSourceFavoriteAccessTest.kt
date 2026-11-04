package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class MainSourceFavoriteAccessTest {
    @Test fun undeclaredFavoritesNeverRequestLoginEvenForAuthenticatedSources() {
        for (signedIn in listOf(false, true)) {
            for (requiresAccount in listOf(false, true)) {
                for (supportsAuthentication in listOf(false, true)) {
                    assertEquals(MainSourceFavoriteAccess.UNSUPPORTED,
                        mainSourceFavoriteAccess(signedIn, requiresAccount, supportsAuthentication, false))
                }
            }
        }
    }

    @Test fun declaredGuestFavoritesDoNotPretendTheSourceHasAnAccount() {
        assertEquals(MainSourceFavoriteAccess.AVAILABLE,
            mainSourceFavoriteAccess(false, false, false, true))
    }

    @Test fun optionalLoginDoesNotBypassAccountFavorites() {
        assertEquals(MainSourceFavoriteAccess.LOGIN_REQUIRED,
            mainSourceFavoriteAccess(false, false, true, true))
        assertEquals(MainSourceFavoriteAccess.AVAILABLE,
            mainSourceFavoriteAccess(true, false, true, true))
    }

    @Test fun requiredLoginCannotBeBypassedByAnAbsentAuthenticationService() {
        assertEquals(MainSourceFavoriteAccess.LOGIN_REQUIRED,
            mainSourceFavoriteAccess(false, true, false, true))
        assertEquals(MainSourceFavoriteAccess.LOGIN_REQUIRED,
            mainSourceFavoriteAccess(false, true, true, true))
    }
}
