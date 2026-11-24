package com.decent.usbaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHidVolumeReportTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun parsesConsumerBitmapWithReportIdAndDetectsRisingEdges() {
        // 标准 Consumer Control（Report ID = 1）：bit0=Vol+, bit1=Vol-, bit2=Play/Pause，其余填充。
        val descriptor = bytes(
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x01,       // Report ID (1)
            0x15, 0x00,       // Logical Minimum (0)
            0x25, 0x01,       // Logical Maximum (1)
            0x75, 0x01,       // Report Size (1)
            0x95, 0x03,       // Report Count (3)
            0x09, 0xE9,       // Usage (Volume Increment)
            0x09, 0xEA,       // Usage (Volume Decrement)
            0x09, 0xCD,       // Usage (Play/Pause)
            0x81, 0x02,       // Input (Data, Var, Abs)
            0x75, 0x05,       // Report Size (5)
            0x95, 0x01,       // Report Count (1)
            0x81, 0x03,       // Input (Const, Var, Abs)
            0xC0,             // End Collection
        )
        val mapping = parseUsbHidVolumeDescriptor(descriptor)
        assertTrue(mapping.isSupported)
        val decoder = UsbHidVolumeReportDecoder(mapping)

        // 不匹配的 Report ID 应忽略。
        assertEquals(emptyList<Int>(), decoder.decode(bytes(0x02, 0x01), 2))
        // 按下 Vol+ 触发一次 +1；持续按住不重复触发；松开后再按触发下一次。
        assertEquals(listOf(1), decoder.decode(bytes(0x01, 0x01), 2))
        assertEquals(emptyList<Int>(), decoder.decode(bytes(0x01, 0x01), 2))
        assertEquals(emptyList<Int>(), decoder.decode(bytes(0x01, 0x00), 2))
        assertEquals(listOf(-1), decoder.decode(bytes(0x01, 0x02), 2))
        assertEquals(emptyList<Int>(), decoder.decode(bytes(0x01, 0x00), 2))
    }

    @Test fun parsesConsumerArrayReportsWithoutReportId() {
        // 无 Report ID 的 16-bit Consumer Control Array 报告。
        val descriptor = bytes(
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            0xA1, 0x01,             // Collection (Application)
            0x19, 0x00,             // Usage Minimum (0)
            0x2A, 0x3C, 0x02,       // Usage Maximum (0x023C)
            0x75, 0x10,             // Report Size (16)
            0x95, 0x01,             // Report Count (1)
            0x81, 0x00,             // Input (Data, Array, Abs)
            0xC0,
        )
        val mapping = parseUsbHidVolumeDescriptor(descriptor)
        assertTrue(mapping.isSupported)
        val decoder = UsbHidVolumeReportDecoder(mapping)

        assertEquals(listOf(1), decoder.decode(bytes(0xE9, 0x00), 2))
        assertEquals(emptyList<Int>(), decoder.decode(bytes(0x00, 0x00), 2))
        assertEquals(listOf(-1), decoder.decode(bytes(0xEA, 0x00), 2))
    }

    @Test fun ignoresVendorDefinedHidInterface() {
        // 厂商自定义调参通道（Usage Page 0xFF00）不应被当作音量键接管。
        val descriptor = bytes(
            0x06, 0x00, 0xFF, // Usage Page (Vendor Defined 0xFF00)
            0x09, 0x01,       // Usage (Vendor Usage 1)
            0xA1, 0x01,       // Collection (Application)
            0x75, 0x08,       // Report Size (8)
            0x95, 0x40,       // Report Count (64)
            0x09, 0xE9,       // 厂商页下的 0xE9 不是 Consumer Volume Up
            0x81, 0x02,       // Input (Data, Var, Abs)
            0xC0,
        )
        assertFalse(parseUsbHidVolumeDescriptor(descriptor).isSupported)
    }
}
