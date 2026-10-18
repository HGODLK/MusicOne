package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlatformLibraryVisibilityTest {
    private val account = MusicAccount(MusicSource.QQ, "a", "昵称", null)

    @Test fun checkingSavedAccountKeepsTheProfileVisible() {
        assertTrue(canShowPlatformLibrary(MusicSource.QQ, account, SessionStatus.CHECKING))
    }

    @Test fun checkingWithoutAnAccountStillShowsTheLoginEntry() {
        assertFalse(canShowPlatformLibrary(MusicSource.QQ, null, SessionStatus.CHECKING))
        assertFalse(canShowPlatformLibrary(MusicSource.KUGOU, account, SessionStatus.CHECKING))
    }

    @Test fun explicitSignOutHidesTheSavedProfile() {
        assertFalse(canShowPlatformLibrary(MusicSource.QQ, account, SessionStatus.SIGNED_OUT))
        assertTrue(canShowPlatformLibrary(MusicSource.QQ, account, SessionStatus.CONNECTED))
    }
}
