package com.musicone.demo

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
}
