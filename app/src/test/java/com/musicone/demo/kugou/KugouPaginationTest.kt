package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KugouPaginationTest {
    @Test fun readsEveryFullPageAndStopsAfterTheFinalPartialPage() {
        val requests = mutableListOf<Pair<Int, Int>>()
        val totalItems = 650
        val result = collectKugouPages(300, { it.optString("ID") }) { page, pageSize ->
            requests += page to pageSize
            val start = (page - 1) * pageSize
            (start until (start + pageSize).coerceAtMost(totalItems)).map { JSONObject().put("ID", it + 1) }
        }

        assertEquals(totalItems, result.size)
        assertEquals(1, result.first().optInt("ID"))
        assertEquals(totalItems, result.last().optInt("ID"))
        assertEquals(listOf(1, 2, 3), requests.map { it.first })
        assertEquals(listOf(300, 300, 300), requests.map { it.second })
    }

    @Test fun stopsWhenTheServiceRepeatsAFullPage() {
        val requests = mutableListOf<Int>()
        val repeatedPage = listOf(1, 2).map { JSONObject().put("ID", it) }
        val result = collectKugouPages(2, { it.optString("ID") }) { page, _ ->
            requests += page
            repeatedPage
        }

        assertEquals(listOf(1, 2), result.map { it.optInt("ID") })
        assertEquals(listOf(1, 2), requests)
    }
}
