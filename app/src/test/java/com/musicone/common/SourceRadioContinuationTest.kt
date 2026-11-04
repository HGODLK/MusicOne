package com.musicone

import com.musicone.source.sdk.SourcePayload
import com.musicone.sourceintegration.SourceRadioContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceRadioContinuationTest {
    private val qq = SourcePayload("qq.radio", 1, "{}")
    private val kugou = SourcePayload("kugou.radio", 1, "{}")

    @Test fun 同一会话同一音源读到上一页() {
        val store = SourceRadioContinuation()
        val session = store.session()
        assertNull(store.read(session, "qq"))
        store.write(session, "qq", qq)
        assertEquals(qq, store.read(session, "qq"))
    }

    @Test fun 其他音源读不到续页() {
        val store = SourceRadioContinuation()
        val session = store.session()
        store.write(session, "qq", qq)
        assertNull(store.read(session, "kugou"))
    }

    @Test fun 新会话后旧续页作废() {
        val store = SourceRadioContinuation()
        store.write(store.session(), "qq", qq)
        store.reset()
        assertNull(store.read(store.session(), "qq"))
    }

    @Test fun 旧会话迟到的结果不写入新会话() {
        val store = SourceRadioContinuation()
        val old = store.session()
        store.reset()
        val current = store.session()
        store.write(current, "kugou", kugou)
        store.write(old, "qq", qq)
        assertEquals(kugou, store.read(current, "kugou"))
    }

    /** 从歌曲开电台时已有曲目不为空，只靠结束旧会话的事件清续页，这里锁定跨音源开播的真实顺序。 */
    @Test fun 从歌曲开新音源电台时不会带上旧音源续页() = runBlocking {
        val store = SourceRadioContinuation()
        var sourceId = "qq"
        val received = mutableListOf<SourcePayload?>()
        val gate = CompletableDeferred<Unit>()
        fun song(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑",
            240_000, 0, 0, "曲", "https://example.com/$id.flac")
        val radio = SourceRadioController(this, { _, _ ->
            val session = store.session()
            received += store.read(session, sourceId)
            store.write(session, sourceId, if (sourceId == "qq") qq else kugou)
            gate.await()
            List(6) { song("$sourceId-$it") }
        }, { SourceRadioQueueSnapshot(listOf(song("seed")), song("seed")) }) { event ->
            if (event is SourceRadioEvent.Mode && !event.active) store.reset()
        }
        radio.startWithSeed(song("seed"))
        yield()
        sourceId = "kugou"
        radio.startWithSeed(song("seed"))
        yield()
        assertEquals(listOf<SourcePayload?>(null, null), received)
        gate.complete(Unit)
        radio.stop()
    }
}
