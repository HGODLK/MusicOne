package com.musicone.demo

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.decent.usbaudio.UsbAudioDevice
import java.util.concurrent.atomic.AtomicInteger

/** 只在用户开启 USB 无损输出后申请设备访问权限。 */
internal object UsbAudioPermissionCoordinator {
    private val requestedDeviceId = AtomicInteger(Int.MIN_VALUE)

    fun handleAttachIntent(context: Context, intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED || !PlaybackOptions.usbLosslessOutput) return
        requestedDeviceId.set(Int.MIN_VALUE)
        UsbAudioOutputStatus.deviceChanged()
    }

    fun prepareForAutomaticSelection() {
        requestedDeviceId.set(Int.MIN_VALUE)
    }

    fun requestConnectedDevice(context: Context) {
        if (!PlaybackOptions.usbLosslessOutput) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    route = UsbAudioOutputRoute.UNSUPPORTED_ANDROID,
                    detail = "USB 无损输出需要 Android 10 或更高版本",
                ),
            )
            return
        }

        val manager = UsbAudioDevice.getInstance(context)
        val device = manager.findUsbAudioDevice()
        if (device == null) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    route = UsbAudioOutputRoute.WAITING_FOR_DEVICE,
                    detail = "等待连接 USB Audio Class 2.0 音频设备",
                ),
            )
            return
        }

        val name = device.productName ?: "USB 音频设备"
        if (manager.hasPermission(device)) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(UsbAudioOutputRoute.READY, name, detail = "设备已授权，播放时自动选择无损通道"),
            )
            UsbAudioOutputStatus.deviceChanged()
            return
        }

        if (!requestedDeviceId.compareAndSet(Int.MIN_VALUE, device.deviceId)) return

        UsbAudioOutputStatus.publish(
            UsbAudioOutputSnapshot(UsbAudioOutputRoute.PERMISSION_REQUIRED, name, detail = "等待 USB 设备访问授权"),
        )
        manager.requestPermission(device) { granted ->
            UsbAudioOutputStatus.publish(
                if (granted) {
                    UsbAudioOutputSnapshot(UsbAudioOutputRoute.READY, name, detail = "设备已授权，播放时自动选择无损通道")
                } else {
                    UsbAudioOutputSnapshot(UsbAudioOutputRoute.FALLBACK, name, detail = "未获得设备权限，继续使用安卓音频输出")
                },
            )
            if (granted) UsbAudioOutputStatus.deviceChanged()
        }
    }
}
