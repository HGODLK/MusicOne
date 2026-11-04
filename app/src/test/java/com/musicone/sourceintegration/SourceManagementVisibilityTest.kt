package com.musicone.sourceintegration

import com.musicone.source.runtime.InstalledSource
import com.musicone.source.sdk.SourceKind
import com.musicone.ui.sources.visibleInSourceManagement
import org.junit.Assert.*
import org.junit.Test

class SourceManagementVisibilityTest {
    private val source = InstalledSource("org.example.main", "signer", 1, null, null, false, null,
        kind = SourceKind.MAIN)

    @Test fun pendingDeletionRemainsVisibleForUndoAndFinalDeletionDisappears() {
        assertTrue(visibleInSourceManagement(source))
        assertTrue(visibleInSourceManagement(source.copy(pendingRemoval = true)))
        assertFalse(visibleInSourceManagement(source.copy(removed = true)))
    }

    @Test fun supplementalRemovalKeepsItsExistingPresentation() {
        assertTrue(visibleInSourceManagement(source.copy(kind = SourceKind.SUPPLEMENTAL, removed = true)))
    }
}
