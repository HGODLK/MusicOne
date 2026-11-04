package com.musicone.source.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SourceRemovalTest {
    private fun installed() = InstalledSource("org.example.main", "identity", 3, 4, 2, false, null,
        kind = com.musicone.source.sdk.SourceKind.MAIN, versionName = "三", pendingVersionName = "四")

    @Test fun undoPreservesRunningPendingAndRollbackVersions() {
        val before = installed()
        val scheduled = before.scheduledForRemoval()
        assertTrue(scheduled.pendingRemoval)
        assertFalse(shouldInstallBundledSource(scheduled, "identity", 10))
        assertEquals(before, scheduled.removalUndone())
        val rollback = before.copy(pendingVersion = 2, pendingVersionName = "二")
        assertEquals(rollback, rollback.scheduledForRemoval().removalUndone())
    }

    @Test fun restartDeletesPrivateStateAndKeepsHostDataAndTombstone() {
        val workspace = Files.createTempDirectory("source-removal").toFile()
        try {
            val root = File(workspace, "source-packs").apply { mkdirs() }
            val state = installed().scheduledForRemoval()
            val privatePaths = listOf("packages/${state.id}/3.msp", "data/${state.id}/preferences/login.json",
                "data/${state.id}-cache/session", "activation-backup/${state.id}/data/session",
                "activation-backup/${state.id}.staging/data/session")
            privatePaths.forEach { path -> File(root, path).apply { parentFile!!.mkdirs(); writeText("private") } }
            val host = File(workspace, "library/tracks").apply { parentFile!!.mkdirs(); writeText("retained") }
            val other = File(root, "data/org.example.other/account").apply { parentFile!!.mkdirs(); writeText("other") }
            assertTrue(privatePaths.all { File(root, it).exists() })
            val removed = completeSourceRemoval(root, state)
            assertTrue(privatePaths.none { File(root, it).exists() })
            assertEquals("retained", host.readText())
            assertEquals("other", other.readText())
            assertTrue(removed.removed)
            assertFalse(removed.pendingRemoval)
            assertNull(removed.activeVersion)
            assertNull(removed.pendingVersion)
            assertNull(removed.rollbackVersion)
            assertEquals("identity", removed.signerSha256)
            assertFalse(shouldInstallBundledSource(removed, "identity", 100))
        } finally { workspace.deleteRecursively() }
    }

    @Test fun oldRegistryDefaultsToActiveAndPendingRemovalSurvivesRestartRead() {
        val original = """{"org.example.main":{"signer":"identity","active":3,"pending":4,"rollback":2,"removed":false}}"""
        assertFalse(SourceRegistryReader.read { original }.states.getValue("org.example.main").pendingRemoval)
        val scheduled = original.replace("\"removed\":false", "\"removed\":false,\"pendingRemoval\":true")
        val state = SourceRegistryReader.read { scheduled }.states.getValue("org.example.main")
        assertTrue(state.pendingRemoval)
        assertEquals(3L, state.activeVersion)
        assertEquals(4L, state.pendingVersion)
        assertEquals(2L, state.rollbackVersion)
    }

    @Test fun invalidIdentityCannotEscapePrivateDirectory() {
        val workspace = Files.createTempDirectory("source-removal-boundary").toFile()
        try {
            val retained = File(workspace, "session").apply { writeText("retained") }
            val failure = runCatching {
                completeSourceRemoval(File(workspace, "source-packs"), installed().copy(id = "../..", pendingRemoval = true))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("retained", retained.readText())
        } finally { workspace.deleteRecursively() }
    }

    @Test fun historicalMainTombstoneCleansLeftoverAccountWithoutChangingSupplementalRemoval() {
        val workspace = Files.createTempDirectory("source-removal-history").toFile()
        try {
            val tombstone = installed().copy(removed = true, activeVersion = null, pendingVersion = null, rollbackVersion = null)
            val session = File(workspace, "data/${tombstone.id}/account").apply { parentFile!!.mkdirs(); writeText("old") }
            assertEquals(tombstone.copy(pendingVersionName = null), completeSourceRemoval(workspace, tombstone))
            assertFalse(session.exists())
            assertEquals(tombstone.copy(pendingVersionName = null), completeSourceRemoval(workspace, tombstone))
            val supplemental = tombstone.copy(kind = com.musicone.source.sdk.SourceKind.SUPPLEMENTAL)
            assertTrue(runCatching { completeSourceRemoval(workspace, supplemental) }.exceptionOrNull() is IllegalStateException)
        } finally { workspace.deleteRecursively() }
    }
}
