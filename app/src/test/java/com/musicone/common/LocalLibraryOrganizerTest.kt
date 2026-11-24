package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LocalLibraryOrganizerTest {
    private fun track(id: String, artists: String, album: String, source: MusicSource = MusicSource.LOCAL) =
        MusicTrack(id, source, "歌曲$id", artists, album, 180_000, 0, 0, "", "")

    @Test fun 歌手按常见分隔符拆分但不拆and符号() {
        assertEquals(listOf("周杰伦", "费玉清"), splitLocalArtists("周杰伦、费玉清"))
        assertEquals(listOf("A", "B", "C"), splitLocalArtists("A / B，C"))
        assertEquals(listOf("A", "B"), splitLocalArtists("A feat. B"))
        assertEquals(listOf("A", "B"), splitLocalArtists("A (feat. B)"))
        assertEquals(listOf("Simon & Garfunkel"), splitLocalArtists("Simon & Garfunkel"))
        assertEquals(emptyList<String>(), splitLocalArtists("未知创作者"))
        assertEquals(listOf("A"), splitLocalArtists("A, a"))
    }

    @Test fun 少于两首不建且只整理本地文件() {
        val tracks = listOf(
            track("1", "A", "专辑甲"), track("2", "A", "专辑甲"), track("3", "A", "专辑乙"),
            track("4", "B", ""), track("5", "A", "专辑甲", MusicSource.QQ),
        )
        val albums = planAutoPlaylists(tracks, AutoPlaylistKind.ALBUM)
        assertEquals(listOf("专辑甲"), albums.map { it.title })
        assertEquals(listOf("1", "2"), albums.single().tracks.map { it.id })
        val artists = planAutoPlaylists(tracks, AutoPlaylistKind.ARTIST)
        assertEquals(listOf("A"), artists.map { it.title })
        assertEquals(listOf("1", "2", "3"), artists.single().tracks.map { it.id })
        assertTrue(albums.single().id.startsWith("library-auto-album-"))
        assertEquals(AutoPlaylistKind.ARTIST, autoPlaylistKind(artists.single().id))
        assertNull(autoPlaylistKind("library-0f3c"))
    }

    @Test fun 同名专辑按主歌手分开且重复整理编号不变() {
        val tracks = listOf(
            track("1", "A", "精选集"), track("2", "A、C", "精选集"),
            track("3", "B", "精选集"), track("4", "B", "精选集"),
        )
        val first = planAutoPlaylists(tracks, AutoPlaylistKind.ALBUM)
        assertEquals(listOf("精选集 · A", "精选集 · B"), first.map { it.title })
        assertEquals(first.map { it.id }, planAutoPlaylists(tracks.reversed(), AutoPlaylistKind.ALBUM).map { it.id }.reversed())
        assertEquals(2, first.map { it.id }.distinct().size)
    }

    @Test fun 删掉的自动歌单不重建且移走的歌曲不加回() {
        val plan = planAutoPlaylists(listOf(track("1", "A", "x"), track("2", "A", "x"), track("3", "A", "x")),
            AutoPlaylistKind.ARTIST).single()
        // 第一次：新建并加入全部。
        val created = resolveAutoPlaylistWrites(listOf(plan), emptySet(), emptyMap()).single()
        assertTrue(created.create)
        assertEquals(3, created.tracks.size)
        // 歌单还在，记录里已有前两首：只补第三首，不新建。
        val added = resolveAutoPlaylistWrites(listOf(plan), setOf(plan.id), mapOf(plan.id to setOf("1", "2"))).single()
        assertFalse(added.create)
        assertEquals(listOf("3"), added.tracks.map { it.id })
        // 全部加入过：即使用户移走了其中一首也不再写入。
        assertTrue(resolveAutoPlaylistWrites(listOf(plan), setOf(plan.id), mapOf(plan.id to setOf("1", "2", "3"))).isEmpty())
        // 建过但歌单已不存在：用户删掉了，不重建。
        assertTrue(resolveAutoPlaylistWrites(listOf(plan), emptySet(), mapOf(plan.id to setOf("1"))).isEmpty())
        assertEquals("已新建 2 个歌单，补充 3 首歌曲", AutoOrganizeResult(2, 3).summary())
        assertEquals("没有需要新建的歌单", AutoOrganizeResult(0, 0).summary())
    }
}
