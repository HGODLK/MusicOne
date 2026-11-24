package com.musicone

import org.junit.Assert.*
import org.junit.Test

class QueueEmptiedExitTest {
    private val song = MusicTrack("qq-1", MusicSource.QQ, "歌曲", "歌手", "", 240_000, 0, 0, "", "")

    @Test fun 有歌变成清空才进入退场并保留最后一首() {
        val exit = QueueEmptiedExit()
        exit.update(null, queueEmpty = true)
        assertFalse(exit.leaving)
        exit.update(song, queueEmpty = false)
        exit.update(null, queueEmpty = true)
        assertTrue(exit.leaving)
        assertEquals(song, exit.lastTrack)
        // 退场中重复组合不改变状态；结束后再次组合也不会重新进入。
        exit.update(null, queueEmpty = true)
        assertTrue(exit.leaving)
        exit.finish()
        exit.update(null, queueEmpty = true)
        assertFalse(exit.leaving)
    }

    @Test fun 退场中有新歌立即作废() {
        val exit = QueueEmptiedExit()
        exit.update(song, queueEmpty = false)
        exit.update(null, queueEmpty = true)
        val next = song.copy(id = "qq-2")
        exit.update(next, queueEmpty = false)
        assertFalse(exit.leaving)
        assertEquals(next, exit.lastTrack)
    }

    @Test fun 没有当前歌但队列还在时不算清空() {
        val exit = QueueEmptiedExit()
        exit.update(song, queueEmpty = false)
        exit.update(null, queueEmpty = false)
        assertFalse(exit.leaving)
    }
}
