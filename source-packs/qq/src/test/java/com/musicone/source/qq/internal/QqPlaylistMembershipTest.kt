package com.musicone.source.qq.internal

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class QqPlaylistMembershipTest {
    private val owner = "123456"
    private val playlist = MusicPlaylist("qq-9001", MusicSource.QQ, "日语", owner, "", 2, 0, 0, "日", emptyList(),
        isOwned = true, providerData = mapOf("qqDirectoryId" to "7"))
    private val tracks = mapOf("qq-a" to setOf("qq-a", "qq-111"), "qq-b" to setOf("qq-b"))

    private fun store() = QqPlaylistMembershipStore(Files.createTempDirectory("qq-membership").toFile())

    @Test
    fun 版本没变直接用本地索引不重拉() = runBlocking {
        var version = QqDirectoryVersion(7, 100, 2)
        var loads = 0
        val membership = QqPlaylistMembership(store(), loadVersions = { mapOf(7L to version) }, versionTtlMs = 0)
        assertNull(membership.cached(owner, playlist.id, tracks))
        assertEquals(setOf("qq-a"), membership.refresh(owner, "cookie", playlist, tracks) { loads++; setOf("qq-111") })
        assertEquals(setOf("qq-a"), membership.cached(owner, playlist.id, tracks))
        assertEquals(setOf("qq-a"), membership.refresh(owner, "cookie", playlist, tracks) { loads++; emptySet() })
        assertEquals(1, loads)
        version = QqDirectoryVersion(7, 200, 3)
        assertEquals(setOf("qq-b"), membership.refresh(owner, "cookie", playlist, tracks) { loads++; setOf("qq-b") })
        assertEquals(2, loads)
    }

    @Test
    fun 本机写入后直接改索引并接受服务端新版本() = runBlocking {
        var version = QqDirectoryVersion(7, 100, 2)
        var loads = 0
        val membership = QqPlaylistMembership(store(), loadVersions = { mapOf(7L to version) }, versionTtlMs = 60_000)
        membership.refresh(owner, "cookie", playlist, tracks) { loads++; setOf("qq-111") }
        membership.applyWrite(owner, playlist.id, setOf("qq-b"), add = true)
        assertEquals(setOf("qq-a", "qq-b"), membership.cached(owner, playlist.id, tracks))
        version = QqDirectoryVersion(7, 300, 3)
        assertEquals(setOf("qq-a", "qq-b"), membership.refresh(owner, "cookie", playlist, tracks) { loads++; emptySet() })
        assertEquals(1, loads)
        membership.applyWrite(owner, playlist.id, setOf("qq-111", "qq-a"), add = false)
        assertEquals(setOf("qq-b"), membership.cached(owner, playlist.id, tracks))
    }

    @Test
    fun 版本未知时每次核对都整单读取() = runBlocking {
        var loads = 0
        val membership = QqPlaylistMembership(store(), loadVersions = { null }, versionTtlMs = 0)
        membership.refresh(owner, "cookie", playlist, tracks) { loads++; setOf("qq-b") }
        membership.refresh(owner, "cookie", playlist, tracks) { loads++; setOf("qq-b") }
        assertEquals(2, loads)
        assertEquals(setOf("qq-b"), membership.cached(owner, playlist.id, tracks))
    }

    @Test
    fun 索引与可写歌单写入文件并能重新读出() {
        val directory = Files.createTempDirectory("qq-membership").toFile()
        val first = QqPlaylistMembershipStore(directory)
        first.saveTargets(owner, listOf(playlist, playlist.copy(id = "qq-9002", title = "西语")))
        first.saveEntry(owner, playlist.id, QqPlaylistMembershipStore.Entry("100:2", setOf("qq-a")))
        first.saveEntry(owner, "qq-9002", QqPlaylistMembershipStore.Entry("1:1", setOf("qq-b")))
        // 歌单被删后，可写列表更新时一并清掉它的索引。
        first.saveTargets(owner, listOf(playlist))
        val second = QqPlaylistMembershipStore(directory)
        assertEquals(listOf("日语"), second.targets(owner)?.map { it.title })
        assertEquals("7", second.targets(owner)?.single()?.providerData?.get("qqDirectoryId"))
        assertEquals(setOf("qq-a"), second.entry(owner, playlist.id)?.ids)
        assertNull(second.entry(owner, "qq-9002"))
    }

    @Test
    fun 解析自建歌单版本() {
        val response = JSONObject("""{"code":0,"req":{"code":0,"data":{"v_playlist":[
            {"tid":1,"dirId":7,"songNum":12,"updateTime":1700000000},
            {"tid":2,"dirid":8,"songnum":0,"updateTime":1700000001},
            {"tid":3,"dirId":0,"songNum":1,"updateTime":1}]}}}""")
        val versions = requireNotNull(parseQqDirectoryVersions(response))
        assertEquals("1700000000:12", versions[7L]?.key)
        assertEquals("1700000001:0", versions[8L]?.key)
        assertEquals(2, versions.size)
        assertNull(parseQqDirectoryVersions(JSONObject("""{"code":0,"req":{"code":1000}}""")))
    }
}
