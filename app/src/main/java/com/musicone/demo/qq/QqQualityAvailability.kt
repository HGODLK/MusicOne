package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 记录实际查询候选和同一时刻的策略，不包含账号或音源地址。 */
internal fun qqQualityCandidatesDiagnostic(
    trackId: String,
    candidates: List<AudioQuality>,
    eligibility: DolbyPlaybackEligibility,
) {
    qqDiagnostic("音质查询候选：track=$trackId，候选=${candidates.joinToString { it.name }}，" +
        "设备杜比=${eligibility.decoderSupported}，解锁=${eligibility.unlocked}，" +
        "USB开关=${eligibility.usbOutputEnabled}，USB音频连接=${eligibility.usbAudioConnected}，USB独占=${eligibility.usbExclusive}")
}

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
                if (quality == AudioQuality.DOLBY) qqDiagnostic("杜比音质探测：开始")
                val source = try {
                    resolve(quality)
                } catch (cancelled: CancellationException) {
                    if (quality == AudioQuality.DOLBY) qqDiagnostic("杜比音质探测：取消")
                    throw cancelled
                } catch (error: Exception) {
                    if (quality == AudioQuality.DOLBY) qqDiagnostic("杜比音质探测失败：error=${error::class.java.simpleName}")
                    if (error.stopsPlaybackFallback()) throw error
                    null
                }
                currentCoroutineContext().ensureActive()
                if (quality == AudioQuality.DOLBY) {
                    qqDiagnostic("杜比音质探测结束：实际=${source?.actualQuality?.name}，" +
                        "试听=${source?.trial}，待验证=${source?.verificationPending}")
                }
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
