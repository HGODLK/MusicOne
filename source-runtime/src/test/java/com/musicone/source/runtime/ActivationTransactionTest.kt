package com.musicone.source.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ActivationTransactionTest {
    private fun scenario(block: (File, File, ActivationTransaction) -> Unit) {
        val root = Files.createTempDirectory("source-activation").toFile()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "session").writeText("valid-session")
        try { block(root, data, ActivationTransaction(File(root, "transaction"), data)) }
        finally { root.deleteRecursively() }
    }
    @Test fun incompleteBackupNeverReplacesOriginalData() = scenario { root, data, transaction ->
        File(root, "transaction.staging/data").mkdirs()
        File(root, "transaction.staging/data/session").writeText("partial")
        transaction.recover(1, 2)
        assertEquals("valid-session", File(data, "session").readText())
        assertFalse(File(root, "transaction.staging").exists())
    }
    @Test fun crashBeforeRegistryCommitRestoresCompleteSnapshot() = scenario { _, data, transaction ->
        transaction.begin(2)
        File(data, "session").writeText("broken-new-session")
        transaction.recover(1, 2)
        assertEquals("valid-session", File(data, "session").readText())
    }
    @Test fun crashAfterRegistryCommitPreservesNewData() = scenario { _, data, transaction ->
        transaction.begin(2)
        File(data, "session").writeText("new-valid-session")
        transaction.recover(2, null)
        assertEquals("new-valid-session", File(data, "session").readText())
    }
    @Test fun restoreCanRepeatAfterOldDirectoryWasMoved() = scenario { root, data, transaction ->
        transaction.begin(2)
        assertTrue(data.renameTo(File(root, "transaction/displaced")))
        transaction.restore()
        transaction.restore()
        assertEquals("valid-session", File(data, "session").readText())
        transaction.finish()
    }
    @Test fun sameVersionCommittedInstallKeepsNewData() = scenario { _, data, transaction ->
        transaction.begin(4, "replacement")
        File(data, "session").writeText("new-session")
        transaction.recover(4, null, "replacement")
        assertEquals("new-session", File(data, "session").readText())
    }
    @Test fun sameVersionWrongInstallCannotMasqueradeAsCommitted() = scenario { _, data, transaction ->
        transaction.begin(4, "replacement")
        File(data, "session").writeText("broken")
        transaction.recover(4, null, "original")
        assertEquals("valid-session", File(data, "session").readText())
    }
    @Test fun historicalTransactionWithoutInstallMarkerStillRecovers() = scenario { root, data, transaction ->
        transaction.begin(2)
        File(root, "transaction/target-install").delete()
        File(data, "session").writeText("broken")
        transaction.recover(1, 2)
        assertEquals("valid-session", File(data, "session").readText())
    }
}
