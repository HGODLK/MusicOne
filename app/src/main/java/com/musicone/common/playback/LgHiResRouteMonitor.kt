package com.musicone

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** 音频设备切换时和 USB 走同一个串行重建入口，防止两边同时抢占播放器。 */
internal class LgHiResRouteMonitor(context: Context, private val changed: (Boolean) -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed(false)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed(
            removedDevices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES })
    }
    fun start() = audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    fun release() = audio.unregisterAudioDeviceCallback(callback)
}
