package com.musicone.source.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceImportPolicyTest {
    private val current = PackageIdentity("org.example.source", "trusted-signer", 4)
    @Test fun sameIdentitySameVersionDoesNotReinstall() {
        assertEquals(ImportDecision.ALREADY_INSTALLED, SourceImportPolicy.decide(current, current, false, sameContent = true))
    }
    @Test fun sameVersionWithDifferentContentCanBeReinstalled() {
        assertEquals(ImportDecision.UPDATE_ON_RESTART, SourceImportPolicy.decide(current, current, false))
    }
    @Test fun testImportsAllowDowngradesByDefaultButExplicitRestrictionStillWorks() {
        val lower = current.copy(versionCode = 3)
        assertThrows(IllegalArgumentException::class.java) { SourceImportPolicy.decide(current, lower, false) }
        assertEquals(ImportDecision.UPDATE_ON_RESTART, SourceImportPolicy.decide(current, lower))
        assertEquals(ImportDecision.UPDATE_ON_RESTART, SourceImportPolicy.decide(current, lower, true))
    }
    @Test fun differentSignerCannotStealSameSourceData() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceImportPolicy.decide(current, current.copy(signerSha256 = "attacker", versionCode = 5), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceImportPolicy.decide(current, current.copy(signerSha256 = "attacker"), true, sameContent = true)
        }
    }
    @Test fun comparableVersionUsesLongNotDisplayString() {
        assertEquals(ImportDecision.UPDATE_ON_RESTART,
            SourceImportPolicy.decide(current, current.copy(versionCode = 10), false))
    }
    @Test fun selectingAnExistingActiveOrRollbackVersionChangesThePendingTarget() {
        val pending = current.copy(versionCode = 6)
        assertEquals(ImportDecision.UPDATE_ON_RESTART,
            SourceImportPolicy.decide(pending, current, true))
        assertEquals(ImportDecision.UPDATE_ON_RESTART,
            SourceImportPolicy.decide(pending, current.copy(versionCode = 3), true))
    }
}
