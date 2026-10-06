package com.musicone.demo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object BluetoothAudioRoute {
    private val mutable = MutableStateFlow<String?>(null)
    val deviceName = mutable.asStateFlow()

    fun publish(device: AudioDeviceInfo?) {
        mutable.value = device?.takeIf { isBluetoothAudioDevice(it.type) }
            ?.productName?.toString()?.trim()?.ifBlank { "蓝牙音频设备" }
    }
}

internal fun isBluetoothAudioDevice(type: Int): Boolean = type in setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
)

/** 监听真正用于播放的 AudioTrack 路由，不把仅连接但未使用的蓝牙设备当作输出。 */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingAudioTrackProvider : DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
    private val handler = Handler(Looper.getMainLooper())
    private var current: AudioTrack? = null
    private var closed = false
    private val listener = AudioTrack.OnRoutingChangedListener { track ->
        if (track === current) BluetoothAudioRoute.publish(track.routedDevice)
    }

    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
        context: Context?,
    ): AudioTrack = DefaultAudioSink.AudioTrackProvider.DEFAULT
        .getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context).also { track ->
            handler.post {
                if (!closed) {
                    current?.removeOnRoutingChangedListener(listener)
                    current = track
                    track.addOnRoutingChangedListener(listener, handler)
                    BluetoothAudioRoute.publish(track.routedDevice)
                }
            }
        }

    fun release() {
        closed = true
        current?.removeOnRoutingChangedListener(listener)
        current = null
        BluetoothAudioRoute.publish(null)
    }
}

internal fun qualityOutputDeviceName(usb: UsbAudioOutputSnapshot, bluetooth: String?): String? =
    if (usb.route == UsbAudioOutputRoute.DIRECT_UAC2 || usb.route == UsbAudioOutputRoute.SYSTEM_BIT_PERFECT) {
        usb.deviceName
    } else bluetooth ?: usb.deviceName
