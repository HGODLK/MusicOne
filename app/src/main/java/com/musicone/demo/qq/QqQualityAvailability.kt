package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 按真实播放链路验证，每次最多两档；已确认档位立即交付菜单。 */
internal suspend fun probeQqAvailableQualities(
    requested: List<AudioQuality>,
    onProgress: (List<AudioQuality>) -> Unit = {},
    resolve: suspend (AudioQuality) -> PlaybackSource,
): List<AudioQuality> = coroutineScope {
    val qualities = requested.distinct().filter { it in QQ_AUDIO_QUALITIES }
    val permits = Semaphore(2)
    val verified = mutableSetOf<AudioQuality>()
    val progressLock = Any()
    qualities.map { quality ->
        async {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                val source = try {
                    resolve(quality)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (error.stopsPlaybackFallback()) throw error
                    null
                }
                currentCoroutineContext().ensureActive()
                if (source != null && source.actualQuality == quality &&
                    !source.trial && !source.verificationPending) synchronized(progressLock) {
                    verified += quality
                    onProgress(qualities.filter { it in verified })
                }
            }
        }
    }.awaitAll()
    qualities.filter { it in verified }
}
