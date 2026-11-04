package com.musicone.source.runtime

import org.junit.Assert.*
import org.junit.Test

class BundledSourceUpdateTest {
    private val current = InstalledSource("source", "signer", 6, null, 5, false, null)

    @Test fun firstInstallAndNewerSignedBundleAreAccepted() {
        assertTrue(shouldInstallBundledSource(null, "signer", 7))
        assertTrue(shouldInstallBundledSource(current, "signer", 7))
    }

    @Test fun removedNewerAndPendingUserVersionsArePreserved() {
        assertFalse(shouldInstallBundledSource(current.copy(removed = true), "signer", 7))
        assertFalse(shouldInstallBundledSource(current.copy(activeVersion = 8), "signer", 7))
        assertFalse(shouldInstallBundledSource(current.copy(pendingVersion = 5), "signer", 7))
        assertFalse(shouldInstallBundledSource(current.copy(activeVersion = 5, rollbackVersion = 7), "signer", 7))
    }

    @Test fun sameVersionAndDifferentSignerAreNeverReplaced() {
        assertFalse(shouldInstallBundledSource(current, "signer", 6))
        assertFalse(shouldInstallBundledSource(current, "other", 7))
    }
}
