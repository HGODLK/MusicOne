package com.musicone.sourceintegration

import com.musicone.MusicOnePage
import org.junit.Assert.*
import org.junit.Test
import com.musicone.source.sdk.SourcePageRequest

class SourcePublicRootPageTest {
    @Test fun onlyExplicitHomeAndMyCanUseRootPresentation() {
        val source = "org.example.source"
        assertTrue(isSourceRootPageRequest(SourcePageRequest.Home(source)))
        assertTrue(isSourceRootPageRequest(SourcePageRequest.My(source)))
        assertFalse(isSourceRootPageRequest(SourcePageRequest.Search(source)))
        assertFalse(isSourceRootPageRequest(SourcePageRequest.Recent(source)))
        assertFalse(isSourceRootPageRequest(SourcePageRequest.Settings(source)))
    }

    @Test fun rootScrollOnlyAcceptsBothExistingPrimaryPages() {
        assertEquals(MusicOnePage.HOME, sourceRootScrollPage("home"))
        assertEquals(MusicOnePage.MY, sourceRootScrollPage("my"))
        assertThrows(IllegalArgumentException::class.java) { sourceRootScrollPage("search") }
    }
}
