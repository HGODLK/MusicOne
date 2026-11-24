package com.musicone.source.subsonic.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubsonicPlaylistMembershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = "http://nas.local|alice"
    private fun playlist(id: String, changed: String, count: Int = 2, readonly: Boolean = false) =
        SubsonicPlaylist(id, "歌单$id", "", "alice", false, count, "", readonly, changed)

    @Test fun 版本没变不重拉_变了才重拉() {
        val membership = SubsonicPlaylistMembership(temporary.newFolder(), versionTtlMs = 0)
        var lists = listOf(playlist("1", "2026-11-01T00:00:00Z"))
        var loads = 0
        assertNull(membership.cached(server, "1"))
        assertEquals(setOf("a", "b"), membership.refresh(server, "1", { lists }) { loads++; setOf("a", "b") })
        assertEquals(setOf("a", "b"), membership.refresh(server, "1", { lists }) { loads++; emptySet() })
        assertEquals(1, loads)
        lists = listOf(playlist("1", "2026-11-02T00:00:00Z", 1))
        assertEquals(setOf("c"), membership.refresh(server, "1", { lists }) { loads++; setOf("c") })
        assertEquals(2, loads)
        assertEquals(setOf("c"), membership.cached(server, "1"))
    }

    @Test fun 本机写入后改索引并接受下一次版本() {
        val membership = SubsonicPlaylistMembership(temporary.newFolder(), versionTtlMs = 60_000)
        var lists = listOf(playlist("1", "t1"))
        var loads = 0
        membership.refresh(server, "1", { lists }) { loads++; setOf("a") }
        membership.applyWrite(server, "1", setOf("b"), add = true)
        assertEquals(setOf("a", "b"), membership.cached(server, "1"))
        lists = listOf(playlist("1", "t2", 2))
        assertEquals(setOf("a", "b"), membership.refresh(server, "1", { lists }) { loads++; emptySet() })
        assertEquals(1, loads)
        membership.applyWrite(server, "1", setOf("a"), add = false)
        assertEquals(setOf("b"), membership.cached(server, "1"))
    }

    @Test fun 服务器不返回修改时间时每次整单读取() {
        val membership = SubsonicPlaylistMembership(temporary.newFolder(), versionTtlMs = 0)
        var loads = 0
        repeat(2) { membership.refresh(server, "1", { listOf(playlist("1", "")) }) { loads++; setOf("a") } }
        assertEquals(2, loads)
    }

    @Test fun 可写歌单写入文件_删掉的歌单清索引() {
        val folder = temporary.newFolder()
        val first = SubsonicPlaylistMembership(folder, versionTtlMs = 0)
        val lists = listOf(playlist("1", "t1"), playlist("2", "t1"), playlist("3", "t1", readonly = true))
        first.refresh(server, "1", { lists }) { setOf("a") }
        first.refresh(server, "2", { lists }) { setOf("b") }
        first.saveTargets(server, listOf(lists[0]), lists.filter { it.id != "2" })
        val second = SubsonicPlaylistMembership(folder)
        assertEquals(listOf("歌单1"), second.targets(server)?.map { it.name })
        assertEquals("t1", second.targets(server)?.single()?.changed)
        assertEquals(setOf("a"), second.cached(server, "1"))
        assertNull(second.cached(server, "2"))
        assertNull(second.targets("http://other|bob"))
    }

    @Test fun 解析修改时间() {
        val parsed = JSONObject("""{"id":"9","name":"夜","songCount":3,"changed":"2026-11-17T01:02:03Z"}""").playlist()
        assertEquals("2026-11-17T01:02:03Z", parsed?.changed)
        assertEquals(parsed, parsed?.toJson()?.playlist())
    }
}
