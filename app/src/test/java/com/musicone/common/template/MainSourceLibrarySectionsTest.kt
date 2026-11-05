package com.musicone

import com.musicone.source.sdk.SourceLibrarySection
import org.junit.Assert.assertEquals
import org.junit.Test

class MainSourceLibrarySectionsTest {
    @Test fun undeclaredRemoteSectionsAreHiddenButLocalStays() {
        assertEquals(listOf(MY_SECTION_LOCAL), mainSourceLibrarySections(emptyList()))
        assertEquals(listOf(MY_SECTION_SAVED, MY_SECTION_LOCAL),
            mainSourceLibrarySections(listOf(SourceLibrarySection.SAVED_COLLECTIONS)))
        assertEquals(listOf(MY_SECTION_CREATED, MY_SECTION_SAVED, MY_SECTION_LOCAL),
            mainSourceLibrarySections(SourceLibrarySection.entries))
    }

    @Test fun savedSectionFallsBackToFirstAvailable() {
        // 换到不支持创建歌单的音源时，原先选中的“创建”回到第一个可用分区。
        assertEquals(MY_SECTION_LOCAL, listOf(MY_SECTION_LOCAL).resolveSection(MY_SECTION_CREATED))
        assertEquals(MY_SECTION_SAVED, listOf(MY_SECTION_SAVED, MY_SECTION_LOCAL).resolveSection(MY_SECTION_SAVED))
    }
}
