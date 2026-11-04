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
}
