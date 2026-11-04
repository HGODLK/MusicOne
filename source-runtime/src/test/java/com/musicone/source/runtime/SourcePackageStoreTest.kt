package com.musicone.source.runtime

import com.musicone.source.sdk.SourceDescriptor
import com.musicone.source.sdk.SourceKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SourcePackageStoreTest {
    private val id = "org.example.source"
    private fun descriptor(version: Long) = SourceDescriptor(id, "测试", SourceKind.SUPPLEMENTAL, version, "$version", emptySet())

    private fun scenario(block: (File, SourcePackageStore, InstalledSource) -> Unit) {
        val root = Files.createTempDirectory("source-install").toFile()
        try {
            File(root, "packages/$id/4.mscp").apply { parentFile!!.mkdirs(); writeText("old-package") }
            block(root, SourcePackageStore(root), InstalledSource(id, "signer", 4, null, null, false, null))
        } finally { root.deleteRecursively() }
    }

    @Test fun sameVersionReplacementRetainsOldFileAndSupportsRollbackBothWays() = scenario { root, store, old ->
        val incoming = File(root, "incoming.mscp").apply { writeText("new-package") }
        val next = store.retain(id, 4, "mscp", incoming, old)
        assertNotEquals(old.activePackage, next)
        assertEquals("old-package", store.file(id, old.activePackage!!).readText())
        val pending = old.withPending(next, descriptor(4))
        val restarted = SourceRegistryReader.read { SourceRegistryWriter.write(listOf(pending)) }.states.getValue(id)
        assertEquals(pending, restarted)
        val active = restarted.activated(next, descriptor(4))
        assertEquals(4L, active.activeVersion)
        assertEquals(4L, active.rollbackVersion)
        assertEquals(old.activePackage, active.rollbackPackage)
        store.cleanup(active)
        assertEquals("new-package", store.file(id, active.activePackage!!).readText())
        assertEquals("old-package", store.file(id, active.rollbackPackage!!).readText())
        val rolledBack = active.withPending(active.rollbackPackage!!, descriptor(4))
            .activated(active.rollbackPackage!!, descriptor(4))
        assertEquals(old.activePackage, rolledBack.activePackage)
        assertEquals(next, rolledBack.rollbackPackage)
    }

    @Test fun selectingExistingActivePackageReplacesDifferentPendingChoice() = scenario { root, store, old ->
        val other = store.retain(id, 5, "mscp", File(root, "other.mscp").apply { writeText("newer") }, old)
        val pending = old.withPending(other, descriptor(5))
        val incoming = File(root, "incoming.mscp").apply { writeText("old-package") }
        assertFalse(store.sameContent(id, pending.pendingPackage!!, incoming))
        val target = store.retain(id, 4, "mscp", incoming, pending)
        assertEquals(old.activePackage, target)
        val selected = pending.withPending(target, descriptor(4))
        assertEquals(old.activePackage, selected.pendingPackage)
        assertEquals(old.activePackage, selected.activated(target, descriptor(4)).activePackage)
    }

    @Test fun downgradeCanReplaceDifferentContentAtRetainedVersion() = scenario { root, store, old ->
        File(root, "packages/$id/3.mscp").writeText("previous-three")
        val state = old.copy(rollbackVersion = 3)
        val next = store.retain(id, 3, "mscp", File(root, "incoming.mscp").apply { writeText("different-three") }, state)
        assertNotEquals(state.rollbackPackage, next)
        assertEquals("previous-three", store.file(id, state.rollbackPackage!!).readText())
        val activated = state.withPending(next, descriptor(3)).activated(next, descriptor(3))
        assertEquals(3L, activated.activeVersion)
        assertEquals(old.activePackage, activated.rollbackPackage)
        store.cleanup(activated)
        assertEquals("old-package", store.file(id, activated.rollbackPackage!!).readText())
        assertFalse(File(root, "packages/$id/3.mscp").exists())
    }

    @Test fun failedActivationRetainsOriginalPackageAndRestoresData() = scenario { root, store, old ->
        val data = File(root, "data/$id").apply { mkdirs() }
        File(data, "session").writeText("signed-in")
        val target = store.retain(id, 4, "mscp", File(root, "incoming.mscp").apply { writeText("failing") }, old)
        val transaction = ActivationTransaction(File(root, "activation-backup/$id"), data)
        transaction.begin(target.versionCode, target.installId)
        File(data, "session").writeText("corrupted-by-new-initializer")
        transaction.recover(4, 4, old.activeInstallId, target.installId)
        assertEquals("signed-in", File(data, "session").readText())
        assertEquals("old-package", store.file(id, old.activePackage!!).readText())
    }

    @Test fun removalClearsInstallationIdsAndIdenticalContentReusesFile() = scenario { root, store, old ->
        val incoming = File(root, "incoming.mscp").apply { writeText("old-package") }
        assertTrue(store.sameContent(id, old.activePackage!!, incoming))
        assertEquals(old.activePackage, store.retain(id, 4, "mscp", incoming, old))
        val removed = old.copy(activeInstallId = "installed-a", pendingVersion = 4,
            pendingInstallId = "installed-b", rollbackVersion = 3, rollbackInstallId = "installed-c").withoutPackages()
        assertNull(removed.activePackage)
        assertNull(removed.activeInstallId)
        assertNull(removed.pendingInstallId)
        assertNull(removed.rollbackInstallId)
    }
}
