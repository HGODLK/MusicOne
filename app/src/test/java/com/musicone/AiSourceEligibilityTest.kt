package com.musicone

import com.musicone.source.sdk.SourceKind
import org.junit.Assert.*
import org.junit.Test

class AiSourceEligibilityTest {
    @Test fun confirmedCurrentMainIsFirstAndOtherMembersAreSecond() {
        assertEquals(1, aiSearchTier(SourceKind.MAIN, true, true, true))
        assertEquals(2, aiSearchTier(SourceKind.MAIN, false, true, true))
        assertEquals(2, aiSearchTier(SourceKind.SUPPLEMENTAL, false, true, true))
    }

    @Test fun unknownMainOnlyParticipatesWhenCurrentlySelected() {
        assertEquals(2, aiSearchTier(SourceKind.MAIN, true, true, null))
        assertEquals(2, aiSearchTier(SourceKind.MAIN, true, false, false))
        assertNull(aiSearchTier(SourceKind.MAIN, false, true, null))
        assertNull(aiSearchTier(SourceKind.MAIN, false, false, false))
        assertEquals("unconfirmed", aiMembershipStatus(true, null))
    }

    @Test fun supplementalWithoutDetectorDoesNotRequireMembership() {
        assertEquals(2, aiSearchTier(SourceKind.SUPPLEMENTAL, false, false, false))
        assertEquals("not_declared", aiMembershipStatus(false, false))
        assertNull(aiSearchTier(SourceKind.SUPPLEMENTAL, false, true, null))
    }

    @Test fun explicitExclusionsRemainBinding() {
        assertTrue(aiSourceExcluded("不要用B站", listOf("bilibili", "哔哩哔哩"), false))
        assertTrue(aiSourceExcluded("只用当前音源", listOf("qq", "QQ音乐"), false))
        assertFalse(aiSourceExcluded("找几首歌", listOf("bilibili"), false))
    }
}
