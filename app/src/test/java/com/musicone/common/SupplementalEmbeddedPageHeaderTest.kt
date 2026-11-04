package com.musicone

import com.musicone.source.sdk.SourceRemoteCollection
import com.musicone.source.sdk.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class SupplementalEmbeddedPageHeaderTest {
    @Test fun innerNavigationProjectsItsCurrentTitleAndUnloadingRestoresTheOuterHeader() {
        val header = SupplementalEmbeddedPageHeader()
        val owner = Any()
        header.update(owner, "音乐收藏夹")
        assertEquals("音乐收藏夹", header.title)
        header.update(owner, "内层收藏夹")
        assertEquals("内层收藏夹", header.title)
        header.clear(owner)
        assertNull(header.title)
    }

    @Test fun outgoingEmbeddedPageCannotClearTheNewPagesTitle() {
        val header = SupplementalEmbeddedPageHeader()
        val oldPage = Any()
        val newPage = Any()
        header.update(oldPage, "旧页面")
        header.update(newPage, "新页面")
        header.clear(oldPage)
        assertEquals("新页面", header.title)
        header.clear()
        assertNull(header.title)
    }

    @Test fun collectionAndAccountHeadersUseTheCurrentInnerPageState() {
        val collection = SourceRemoteCollection(TrackIdentity("test.supplemental", null, "folder"), "当前收藏夹")
        assertEquals("当前收藏夹", supplementalPageTitle(
            SupplementalState(page = SupplementalPage.FOLDER, selectedCollection = collection), "测试音源"))
        assertEquals("登录测试音源", supplementalPageTitle(SupplementalState(page = SupplementalPage.ACCOUNT), "测试音源"))
    }

    @Test fun outgoingPageCannotRemoveTheCurrentInnerBackHandler() {
        val header = SupplementalEmbeddedPageHeader()
        val oldPage = Any()
        val currentPage = Any()
        var oldCalls = 0
        var currentCalls = 0
        header.updateBack(oldPage) { oldCalls++; true }
        header.updateBack(currentPage) { currentCalls++; true }
        header.clear(oldPage)
        assertTrue(header.back())
        assertEquals(0, oldCalls)
        assertEquals(1, currentCalls)
        header.clear(currentPage)
        assertFalse(header.back())
    }
}
