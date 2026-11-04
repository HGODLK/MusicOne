package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSourceSearchDismissalTest {
    @Test fun closingInputClearsDraftSuggestionsAndTheirStatus() {
        val closed = MainSourceSearchState(opened = true, query = "搜索词", suggestions = listOf("联想"),
            suggesting = true, suggestionError = "旧错误").withSearchMenuClosed()
        assertFalse(closed.opened)
        assertEquals("", closed.query)
        assertTrue(closed.suggestions.isEmpty())
        assertFalse(closed.suggesting)
        assertNull(closed.suggestionError)
    }

    @Test fun closingResultsKeepsHistoryAndCachedResultIdentity() {
        val page = MainSourceSearchPage(page = 1)
        val closed = MainSourceSearchState(opened = true, full = true, query = "搜索词", submitted = "搜索词",
            history = listOf("搜索词"), pages = mapOf(MainSourceSearchTab.ALL to page),
            singer = MainSourceArtist("123", "歌手", null, MusicSource.QQ)).withSearchMenuClosed()
        assertFalse(closed.full)
        assertNull(closed.singer)
        assertEquals("", closed.query)
        assertEquals(listOf("搜索词"), closed.history)
        assertEquals("搜索词", closed.submitted)
        assertEquals(page, closed.pages[MainSourceSearchTab.ALL])
    }
}
