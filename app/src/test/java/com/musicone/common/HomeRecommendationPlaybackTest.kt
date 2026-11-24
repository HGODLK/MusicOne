package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecommendationPlaybackTest {
    private fun action(
        track: MusicSource = MusicSource.KUGOU,
        radioActive: Boolean = false,
        radioSource: MusicSource = MusicSource.QQ,
        hasQueue: Boolean = true,
        keepQueue: Boolean = false,
        radioAvailable: Boolean = true,
    ) = homeRecommendationAction(track, radioActive, radioSource, hasQueue, keepQueue, radioAvailable)

    @Test fun 开关关闭时普通队列替换为电台() {
        assertEquals(HomeRecommendationAction.START_RADIO, action())
    }

    @Test fun 开关开启时普通队列只插入() {
        assertEquals(HomeRecommendationAction.INSERT_IN_QUEUE, action(keepQueue = true))
    }

    @Test fun 没有队列时两种设置都开电台() {
        assertEquals(HomeRecommendationAction.START_RADIO, action(hasQueue = false))
        assertEquals(HomeRecommendationAction.START_RADIO, action(hasQueue = false, keepQueue = true))
    }

    @Test fun 同音源电台中两种设置都留在电台() {
        for (keepQueue in listOf(false, true)) {
            assertEquals(HomeRecommendationAction.JOIN_RADIO, action(track = MusicSource.QQ, radioActive = true,
                radioSource = MusicSource.QQ, keepQueue = keepQueue))
        }
    }

    @Test fun 跨音源电台开关关闭时换成新音源电台() {
        assertEquals(HomeRecommendationAction.START_RADIO, action(track = MusicSource.KUGOU, radioActive = true,
            radioSource = MusicSource.QQ))
        assertEquals(HomeRecommendationAction.START_RADIO, action(track = MusicSource.QQ, radioActive = true,
            radioSource = MusicSource.KUGOU))
    }

    @Test fun 跨音源电台开关开启时只插入队列() {
        assertEquals(HomeRecommendationAction.INSERT_IN_QUEUE, action(track = MusicSource.KUGOU, radioActive = true,
            radioSource = MusicSource.QQ, keepQueue = true))
    }

    @Test fun 音源没有电台时不开电台() {
        assertEquals(HomeRecommendationAction.INSERT_IN_QUEUE, action(radioAvailable = false))
        assertEquals(HomeRecommendationAction.INSERT_IN_QUEUE, action(radioAvailable = false, hasQueue = false))
        assertEquals(HomeRecommendationAction.INSERT_IN_QUEUE, action(radioAvailable = false, radioActive = true))
    }

    private fun song(id: String, source: MusicSource = MusicSource.QQ) = MusicTrack(id = id, source = source, title = id,
        artists = "歌手", album = "专辑", durationMs = 180_000L, artworkStart = 0L, artworkEnd = 0L, artworkMark = id, previewUrl = "")
    private fun ids(tracks: List<MusicTrack>) = tracks.map { it.id }

    @Test fun 点击中间一首时上面的不入队() {
        val page = listOf(song("a"), song("b"), song("c"))
        assertEquals(listOf("a", "b", "c"), ids(homeRecommendationBatch(page, 0) { true }))
        assertEquals(listOf("b", "c"), ids(homeRecommendationBatch(page, 1) { true }))
        assertEquals(listOf("c"), ids(homeRecommendationBatch(page, 2) { true }))
        assertEquals(emptyList<String>(), ids(homeRecommendationBatch(page, 3) { true }))
    }

    @Test fun 后续歌曲不可播或跨音源时跳过() {
        val page = listOf(song("a"), song("b"), song("c", MusicSource.KUGOU), song("d"))
        assertEquals(listOf("a", "d"), ids(homeRecommendationBatch(page, 0) { it.id != "b" }))
    }

    @Test fun 整批接在当前歌曲后且原有后续顺延() {
        val queue = listOf(song("h"), song("cur"), song("r1"), song("b"), song("r2"))
        val result = insertTracksAfterCurrent(queue, song("cur"), listOf(song("a"), song("b"), song("c")))
        assertEquals(listOf("h", "cur", "a", "b", "c", "r1", "r2"), ids(result))
    }

    @Test fun 当前歌曲在这批里时随这批重新排位() {
        val queue = listOf(song("h"), song("b"), song("r1"))
        val result = insertTracksAfterCurrent(queue, song("b"), listOf(song("a"), song("b"), song("c")))
        assertEquals(listOf("h", "a", "b", "c", "r1"), ids(result))
    }

    @Test fun 电台窗口整批插入后以第一首为中心并记为已出现() {
        val window = SourceRadioQueueWindow()
        val queue = window.start(listOf(song("r1"), song("r2"), song("r3"), song("r4"), song("r5")), song("cur"))
        val result = window.insertAll(queue, song("cur"), listOf(song("a"), song("b"), song("c")))
        assertEquals(listOf("cur", "a", "b", "c", "r1", "r2", "r3"), ids(result))
        assertEquals(true, window.seenIds().containsAll(listOf("a", "b", "c")))
    }
}
