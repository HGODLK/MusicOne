package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class QqLyricsPreloadWindowTest {
    @Test fun oneFailedLyricDoesNotBlockTheOtherFourOrRestartOnMetadataChanges() = runBlocking {
        val attempted = CopyOnWriteArrayList<String>()
        val done = CompletableDeferred<Unit>()
        val tracks = (1..5).map { track("$it") }
        val window = QqLyricsPreloadWindow(this, { song, _ ->
            attempted += song.id
            if (song.id == "2") error("歌词不可用")
            if (song.id == "5") done.complete(Unit)
        }, 0)
        try {
            window.update(tracks, "QQ:one")
            withTimeout(5_000) { done.await() }
            window.update(tracks.map { it.copy(title = "补全标题") }, "QQ:one")
            assertEquals(listOf("1", "2", "3", "4", "5"), attempted.toList())
        } finally { window.stop() }
    }

    @Test fun switchingAccountsCancelsTheOldWindowBeforeLoadingNewTargets() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val newFinished = CompletableDeferred<Unit>()
        val attempted = CopyOnWriteArrayList<String>()
        val window = QqLyricsPreloadWindow(this, { song, namespace ->
            attempted += "$namespace:${song.id}"
            if (namespace == "QQ:old") { entered.complete(Unit); awaitCancellation() }
            else newFinished.complete(Unit)
        }, 0)
        try {
            window.update(listOf(track("1"), track("2")), "QQ:old")
            withTimeout(5_000) { entered.await() }
            window.update(listOf(track("3")), "QQ:new")
            withTimeout(5_000) { newFinished.await() }
            assertEquals(listOf("QQ:old:1", "QQ:new:3"), attempted.toList())
        } finally { window.stop() }
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "", 240_000, 0, 0, "", "")
}
