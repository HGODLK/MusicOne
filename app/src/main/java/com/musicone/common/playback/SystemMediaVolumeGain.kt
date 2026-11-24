package com.musicone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.pow

/** 把系统媒体音量曲线换算为 USB 独占输出使用的软件增益，并监听系统媒体音量变化广播即时刷新。 */
internal class SystemMediaVolumeGain(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var cachedGain = 1f
    @Volatile private var refreshAfterMs = 0L
    private var localVolume: Int? = null
    private var overriddenSystemVolume = -1
    private var volumeToast: android.widget.Toast? = null
    private var volumeReceiver: BroadcastReceiver? = null

    /** 注册系统媒体音量变化广播，拉动音量条或后台按键时立即失效缓存并回调。 */
    @Synchronized fun startObserving(onChanged: () -> Unit) {
        if (volumeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_VOLUME_CHANGED) return
                val streamType = runCatching {
                    intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                }.getOrDefault(-1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    refreshAfterMs = 0L
                    onChanged()
                }
            }
        }
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)
        if (registered) volumeReceiver = receiver
    }

    @Synchronized fun stopObserving() {
        val receiver = volumeReceiver ?: return
        volumeReceiver = null
        runCatching { applicationContext.unregisterReceiver(receiver) }
    }

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
        if (volume >= maximum) return 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decibels = runCatching {
                // 独占时保留的静音 AudioTrack 指向内置扬声器，音量键也沿用这条媒体音量曲线。
                audioManager.getStreamVolumeDb(
                    AudioManager.STREAM_MUSIC,
                    volume,
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                )
            }.getOrNull()
            val maxDecibels = runCatching {
                audioManager.getStreamVolumeDb(
                    AudioManager.STREAM_MUSIC,
                    maximum,
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                )
            }.getOrNull()
            return usbSystemVolumeGain(volume, maximum, decibels, maxDecibels)
        }
        return usbFallbackVolumeGain(volume, maximum)
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 100L
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}

/**
 * 把系统媒体音量档位与分贝表换算为相对满档的线性增益。
 * 荣耀／华为等机型内置扬声器满档可能带负分贝保护余量，需相对满档分贝归一化，
 * 避免满格系统音量在 USB 独占下仍被大幅衰减。
 */
internal fun usbSystemVolumeGain(
    volume: Int,
    maximum: Int,
    decibels: Float?,
    maxDecibels: Float?,
): Float {
    val ceiling = maximum.coerceAtLeast(1)
    if (volume <= 0) return 0f
    if (volume >= ceiling) return 1f
    if (decibels == Float.NEGATIVE_INFINITY) return 0f
    val reference = maxDecibels?.takeIf { it.isFinite() } ?: 0f
    if (decibels != null && decibels.isFinite() && decibels < reference) {
        return 10.0.pow((decibels - reference) / 20.0).toFloat().coerceIn(0f, 1f)
    }
    return usbFallbackVolumeGain(volume, ceiling)
}

internal fun usbFallbackVolumeGain(volume: Int, maximum: Int): Float {
    val fraction = (volume.toFloat() / maximum.coerceAtLeast(1)).coerceIn(0f, 1f)
    return fraction * fraction
}
