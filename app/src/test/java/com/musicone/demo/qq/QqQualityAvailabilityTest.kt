package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqQualityAvailabilityTest {
    @Test
    fun 不排除已验证HiRes且单档失败不丢失其他音质() = runBlocking {
        val progress = mutableListOf<List<AudioQuality>>()
        val qualities = listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD)
        val result = probeQqAvailableQualities(qualities, progress::add) { quality ->
            if (quality == AudioQuality.LOSSLESS) throw QqPlaybackItemUnavailableException("不可用", 740, "测试")
            source(quality)
        }
        assertEquals(listOf(AudioQuality.HI_RES, AudioQuality.EXHIGH, AudioQuality.STANDARD), result)
        assertEquals(result, progress.last())
    }

    @Test
    fun 试听待确认和错档音源都不能标为可用() = runBlocking {
        val result = probeQqAvailableQualities(listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.STANDARD)) {
            when (it) {
                AudioQuality.HI_RES -> source(AudioQuality.LOSSLESS)
                AudioQuality.LOSSLESS -> source(it).copy(trial = true)
                else -> source(it).copy(verificationPending = true)
            }
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun 先完成的档位立即发布且最多同时检查两档() = runBlocking {
        withTimeout(2_000) {
            val releaseStandard = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val started = Channel<AudioQuality>(Channel.UNLIMITED)
            val progress = Channel<List<AudioQuality>>(Channel.UNLIMITED)
            val task = async {
                probeQqAvailableQualities(listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.HI_RES),
                    onProgress = { progress.trySend(it) }) { quality ->
                    started.send(quality)
                    if (quality == AudioQuality.STANDARD) releaseStandard.await() else release.await()
                    source(quality)
                }
            }
            assertEquals(AudioQuality.STANDARD, started.receive())
            assertEquals(AudioQuality.EXHIGH, started.receive())
            assertTrue(started.tryReceive().isFailure)
            releaseStandard.complete(Unit)
            assertEquals(listOf(AudioQuality.STANDARD), progress.receive())
            assertTrue(!task.isCompleted)
            assertEquals(AudioQuality.HI_RES, started.receive())
            release.complete(Unit)
            assertEquals(3, task.await().size)
        }
    }

    @Test
    fun 会话失效必须传出且未启动的档位不继续请求() = runBlocking {
        val calls = mutableListOf<AudioQuality>()
        val error = runCatching {
            probeQqAvailableQualities(QQ_AUDIO_QUALITIES) {
                calls += it
                throw QqCredentialExpiredException(104401, "测试")
            }
        }.exceptionOrNull()
        assertTrue(error is QqCredentialExpiredException)
        assertEquals(1, calls.size)
    }

    private fun source(quality: AudioQuality) = PlaybackSource(
        "https://example.com/audio", quality, quality, quality.bitRate, "flac", false,
    )
}
