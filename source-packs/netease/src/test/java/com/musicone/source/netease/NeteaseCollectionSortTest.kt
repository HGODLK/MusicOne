package com.musicone.source.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseCollectionSortTest {
    private val songs = listOf("c", "a", "b")

    @Test fun 默认保持接口顺序() {
        assertEquals(songs, sortedNeteaseCollection(songs, "ADDED_DESC") { it })
        assertEquals(songs, sortedNeteaseCollection(songs, null) { it })
    }

    @Test fun 旧到新是接口顺序的倒序() = assertEquals(listOf("b", "a", "c"), sortedNeteaseCollection(songs, "ADDED_ASC") { it })

    @Test fun 按名称排序() = assertEquals(listOf("a", "b", "c"), sortedNeteaseCollection(songs, "NAME") { it })

    @Test fun 真实歌单按账号和编号保存() {
        assertEquals("1:100", neteaseCollectionSortKey("100", "1", "1"))
        assertEquals("1:100", neteaseCollectionSortKey("100", null, "1"))
        assertNotEquals(neteaseCollectionSortKey("100", null, "1"), neteaseCollectionSortKey("100", null, "2"))
    }

    @Test fun 未登录或账号不符时交给宿主() {
        assertNull(neteaseCollectionSortKey("100", null, null))
        assertNull(neteaseCollectionSortKey("100", "2", "1"))
    }

    @Test fun 临时歌单交给宿主() {
        assertNull(neteaseCollectionSortKey("daily:2026-11-07", null, "1"))
        assertNull(neteaseCollectionSortKey("recent:songs", null, "1"))
        assertNull(neteaseCollectionSortKey("0", null, "1"))
    }
}
