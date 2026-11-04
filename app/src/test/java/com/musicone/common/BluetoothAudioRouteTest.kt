package com.musicone

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class BluetoothAudioRouteTest {
    @Test fun bluetoothOutputClassificationIncludesClassicLeAndHearingAids() {
        listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID)
            .forEach { assertTrue(isBluetoothAudioDevice(it)) }
        assertFalse(isBluetoothAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(isBluetoothAudioDevice(AudioDeviceInfo.TYPE_USB_DEVICE))
    }

    @Test fun actualExclusiveUsbTakesPriorityButIdleUsbDoesNotHideBluetooth() {
        val usb = UsbAudioOutputSnapshot(UsbAudioOutputRoute.READY, "USB DAC")
        assertEquals("蓝牙耳机", qualityOutputDeviceName(usb, "蓝牙耳机"))
        assertEquals("USB DAC", qualityOutputDeviceName(usb.copy(route = UsbAudioOutputRoute.DIRECT_UAC2), "蓝牙耳机"))
        assertNull(qualityOutputDeviceName(UsbAudioOutputSnapshot(), null))
    }

    @Test fun lgAndSystemRoutesUseTheirActiveDeviceNames() {
        val idleUsb = UsbAudioOutputSnapshot(UsbAudioOutputRoute.READY, "USB DAC")
        assertEquals("LG G7 ThinQ", qualityOutputDeviceName(idleUsb, "旧蓝牙设备", lg = "LG G7 ThinQ"))
        assertEquals("有线耳机", qualityOutputDeviceName(idleUsb, null, system = "有线耳机"))
        assertEquals("本机", qualityOutputDeviceName(idleUsb, null, local = "本机"))
        assertEquals("本机", qualityOutputDeviceName(UsbAudioOutputSnapshot(), null, local = "本机"))
        assertEquals("USB DAC", qualityOutputDeviceName(idleUsb.copy(route = UsbAudioOutputRoute.DIRECT_UAC2),
            null, lg = "LG G7 ThinQ", local = "本机"))
    }
}
