package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class CacheSettingsDraftTest {
    @Test fun changesStayInDraftAndBothQuotasAreConsumedAtomically() {
        val applied = CacheLimits()
        val draft = CacheSettingsDraft()
        draft.select(applied, false, CacheCapacity.OFF)
        draft.select(applied, true, CacheCapacity.GB10)
        assertEquals(CacheCapacity.GB5, applied.total)
        assertEquals(CacheCapacity.OFF, applied.independent)
        assertEquals(CacheLimits(CacheCapacity.OFF, CacheCapacity.GB10), draft.consume())
        assertNull(draft.consume())
    }

    @Test fun repeatedSelectionKeepsOnlyLatestValues() {
        val draft = CacheSettingsDraft()
        draft.select(CacheLimits(), true, CacheCapacity.GB1)
        draft.select(CacheLimits(), false, CacheCapacity.GB20)
        draft.select(CacheLimits(), true, CacheCapacity.GB5)
        assertEquals(CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5), draft.consume())
    }

    @Test fun openingWithoutChangesDoesNotRequestApplicationOrCleanup() {
        assertNull(CacheSettingsDraft().consume())
    }
}
