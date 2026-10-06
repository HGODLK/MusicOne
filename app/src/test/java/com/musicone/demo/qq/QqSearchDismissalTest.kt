package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqSearchDismissalTest {
    @Test fun closingInputClearsDraftSuggestionsAndTheirStatus() {
        val closed = QqSearchState(opened = true, query = "搜索词", suggestions = listOf("联想"),
            suggesting = true, suggestionError = "旧错误").withSearchMenuClosed()
        assertFalse(closed.opened)
        assertEquals("", closed.query)
        assertTrue(closed.suggestions.isEmpty())
        assertFalse(closed.suggesting)
        assertNull(closed.suggestionError)
    }

    @Test fun closingResultsKeepsHistoryAndCachedResultIdentity() {
        val page = QqSearchPage(page = 1)
        val closed = QqSearchState(opened = true, full = true, query = "搜索词", submitted = "搜索词",
            history = listOf("搜索词"), pages = mapOf(QqSearchTab.ALL to page),
            singer = QqSearchSinger("123", "歌手", null)).withSearchMenuClosed()
        assertFalse(closed.full)
        assertNull(closed.singer)
        assertEquals("", closed.query)
        assertEquals(listOf("搜索词"), closed.history)
        assertEquals("搜索词", closed.submitted)
        assertEquals(page, closed.pages[QqSearchTab.ALL])
    }
}
