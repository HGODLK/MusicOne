package com.musicone.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 独立于开关和播放路由跟踪实际 USB 音频连接，未播放或等待授权时也能更新策略。 */
internal object UsbAudioConnectionState {
    var connected by mutableStateOf(false)
        private set
    private val mutableConnected = MutableStateFlow(false)
    val changes = mutableConnected.asStateFlow()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val application = context.applicationContext
        val audio = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usb = application.getSystemService(Context.USB_SERVICE) as UsbManager

        fun refresh() {
            connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isUsbAudioOutput) ||
                usb.deviceList.values.any(::isUsbAudioDevice)
            mutableConnected.value = connected
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(receiver, filter)
        }
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
        initialized = true
    }
}

internal fun isUsbAudioOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}

internal fun isUsbAudioDevice(device: UsbDevice): Boolean =
    (0 until device.interfaceCount).any { index ->
        val audioInterface = device.getInterface(index)
        audioInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            audioInterface.interfaceSubclass == 2 &&
            (0 until audioInterface.endpointCount).any { endpoint ->
                audioInterface.getEndpoint(endpoint).direction == UsbConstants.USB_DIR_OUT
            }
    }
