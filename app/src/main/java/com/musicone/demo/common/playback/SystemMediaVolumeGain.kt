package com.musicone.demo

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.pow

/** 把系统媒体音量曲线换算为 USB 独占输出使用的软件增益。 */
internal class SystemMediaVolumeGain(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var cachedGain = 1f
    @Volatile private var refreshAfterMs = 0L
    private var localVolume: Int? = null
    private var overriddenSystemVolume = -1
    private var volumeToast: android.widget.Toast? = null

    @Synchronized fun adjust(direction: Int) {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (localVolume != null && before != overriddenSystemVolume) localVolume = null
        val current = localVolume ?: before
        val requested = (current + direction).coerceIn(0, maximum)
        if (localVolume == null && !audioManager.isVolumeFixed) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
        }
        val after = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (localVolume != null || (after == before && requested != before)) {
            // 厂商固定音量或未改变媒体档位时，直接调整独占 PCM 的软件增益。
            localVolume = requested
            overriddenSystemVolume = after
            volumeToast?.cancel()
            volumeToast = android.widget.Toast.makeText(applicationContext,
                "USB 音量 ${requested * 100 / maximum}%", android.widget.Toast.LENGTH_SHORT).also { it.show() }
        }
        refreshAfterMs = 0L
    }

    @Synchronized fun current(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now < refreshAfterMs) return cachedGain
        cachedGain = readGain()
        refreshAfterMs = now + REFRESH_INTERVAL_MS
        return cachedGain
    }

    private fun readGain(): Float {
        val systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (localVolume != null && systemVolume != overriddenSystemVolume) localVolume = null
        if (localVolume == null && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) return 0f
        val volume = localVolume ?: systemVolume
        if (volume <= 0) return 0f
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (localVolume != null) return usbFallbackVolumeGain(volume, maximum)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decibels = runCatching {
                // 独占时保留的静音 AudioTrack 指向内置扬声器，音量键也沿用这条媒体音量曲线。
                audioManager.getStreamVolumeDb(
                    AudioManager.STREAM_MUSIC,
                    volume,
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                )
            }.getOrNull()
            if (decibels == Float.NEGATIVE_INFINITY) return 0f
            // 非满档却返回零分贝时不能照搬满幅增益，退回连续的软件音量曲线。
            if (decibels != null && decibels.isFinite() && (decibels < 0f || volume >= maximum)) {
                return 10.0.pow(decibels / 20.0).toFloat().coerceIn(0f, 1f)
            }
        }
        return usbFallbackVolumeGain(volume, maximum)
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 100L
    }
}

internal fun usbFallbackVolumeGain(volume: Int, maximum: Int): Float {
    val fraction = (volume.toFloat() / maximum.coerceAtLeast(1)).coerceIn(0f, 1f)
    return fraction * fraction
}
