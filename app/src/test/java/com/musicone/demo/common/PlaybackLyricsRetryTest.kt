package com.musicone.demo

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackLyricsRetryTest {
    private val track = MusicTrack("song", MusicSource.QQ, "歌曲", "歌手", "", 210_000, 0, 0, "", "")
    private val key = PlaybackRequestKey(track.id, 1)
    private val lyrics = listOf(TimedLyric(1_000L, "恢复歌词"))

    @Test fun timeoutRemainsRetryableAndRealPlaybackRetriesOnce() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, {
            if (++calls == 1) awaitCancellation() else lyrics
        }, { it == key }, {
            updates += it
            if (it.state == LyricLoadState.RETRYABLE) failed.complete(Unit)
            if (it.state == LyricLoadState.READY) ready.complete(Unit)
        }, timeoutMs = 30)
        try {
            loader.load(track, key)
            withTimeout(3_000) { failed.await() }
            assertEquals(1, calls)
            assertEquals(LyricLoadState.RETRYABLE, updates.last().state)
            loader.onPlaybackStarted(key.copy(generation = 0))
            yield()
            assertEquals(1, calls)
            loader.onPlaybackStarted(key)
            withTimeout(3_000) { ready.await() }
            assertEquals(2, calls)
            assertEquals(lyrics, updates.last().lyrics)
        } finally { loader.cancel() }
    }

    @Test fun failureAfterPlaybackStartedAlsoRetriesAndStopsAfterSecondFailure() = runBlocking {
        val exhausted = CompletableDeferred<Unit>()
        var calls = 0
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        val loader = PlaybackLyricsLoader(this, { calls++; throw IOException("弱网") }, { it == key }, {
            updates += it
            if (calls == 2 && it.state == LyricLoadState.RETRYABLE) exhausted.complete(Unit)
        })
        try {
            loader.onPlaybackStarted(key)
            loader.load(track, key)
            withTimeout(3_000) { exhausted.await() }
            repeat(3) { loader.onPlaybackStarted(key); yield() }
            assertEquals(2, calls)
            assertEquals(LyricLoadState.RETRYABLE, updates.last().state)
        } finally { loader.cancel() }
    }

    @Test fun explicitRetryCanRecoverWithoutWaitingForPlayback() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, {
            if (++calls == 1) throw IOException("弱网") else lyrics
        }, { it == key }, {
            if (it.state == LyricLoadState.RETRYABLE) failed.complete(Unit)
            if (it.state == LyricLoadState.READY) ready.complete(Unit)
        })
        try {
            loader.load(track, key)
            withTimeout(3_000) { failed.await() }
            loader.load(track, key, retryAfterPlayback = false)
            withTimeout(3_000) { ready.await() }
            assertEquals(2, calls)
        } finally { loader.cancel() }
    }

    @Test fun confirmedEmptyResultIsUnavailableAndDoesNotRetry() = runBlocking {
        val done = CompletableDeferred<Unit>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, { calls++; emptyList() }, { it == key }, {
            if (it.state == LyricLoadState.UNAVAILABLE) done.complete(Unit)
        })
        try {
            loader.load(track, key)
            withTimeout(3_000) { done.await() }
            loader.onPlaybackStarted(key)
            yield()
            assertEquals(1, calls)
        } finally { loader.cancel() }
    }

    @Test fun switchingPlaybackCancelsPendingRetry() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, { calls++; throw IOException("弱网") }, { it == key }, {
            if (it.state == LyricLoadState.RETRYABLE) failed.complete(Unit)
        })
        loader.load(track, key)
        withTimeout(3_000) { failed.await() }
        loader.cancel()
        loader.onPlaybackStarted(key)
        yield()
        assertEquals(1, calls)
    }

    @Test fun startupDelayDoesNotConsumeTheNetworkTimeout() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val loader = PlaybackLyricsLoader(this, { lyrics }, { it == key }, {
            if (it.state == LyricLoadState.READY) ready.complete(Unit)
        }, timeoutMs = 10)
        try {
            loader.load(track, key, startDelayMs = 40)
            withTimeout(3_000) { ready.await() }
        } finally { loader.cancel() }
    }
}
