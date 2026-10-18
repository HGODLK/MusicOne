package com.decent.usbaudio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbAudioPcmDescriptorsTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val control = bytes(9, 4, 3, 0, 0, 1, 1, 0x20, 0) +
        bytes(8, 0x24, 0x0a, 7, 3, 15, 0, 0) +
        bytes(17, 0x24, 2, 1, 1, 1, 0, 7, 2, 3, 0, 0, 0, 0, 0, 0, 0)

    private fun format(alt: Int, kind: Int = 1, channels: Int = 2, bits: Int = 32, slot: Int = 4): ByteArray =
        bytes(9, 4, 4, alt, 2, 1, 2, 0x20, 0) +
            bytes(16, 0x24, 1, 1, 0, 1, kind, 0, 0, 0, channels, 3, 0, 0, 0, 0) +
            bytes(6, 0x24, 2, 1, slot, bits)

    @Test fun integerPcmAndClockRemainBoundToTheirInterfaceAndChannelCount() {
        val descriptors = UsbAudioPcmDescriptors(control + format(1, bits = 24, slot = 3) + format(2))
        assertEquals(listOf(24, 32), descriptors.formats.map { it.bits })
        val profile = descriptors.formats.last()
        assertEquals(3, profile.controlInterface)
        assertEquals(4, profile.interfaceId)
        assertEquals(2, profile.channels)
        assertEquals(7, descriptors.clockSource(profile) { _, _ -> error("没有选择器") })
    }

    @Test fun floatRawCompositeAndImpossibleSubslotsAreNotIntegerPcmProfiles() {
        val descriptors = UsbAudioPcmDescriptors(control + format(1, kind = 4) + format(2, kind = 0x80) +
            format(3, kind = 5) + format(4, bits = 32, slot = 3) + format(5))
        assertEquals(listOf(5), descriptors.formats.map { it.altSetting })
    }

    @Test fun clockSelectorUsesItsSelectedPinInsteadOfTheFirstClock() {
        val input = control + bytes(8, 0x24, 0x0a, 8, 3, 15, 0, 0) +
            bytes(9, 0x24, 0x0b, 9, 2, 7, 8, 3, 0) +
            bytes(17, 0x24, 2, 1, 1, 1, 0, 9, 2, 3, 0, 0, 0, 0, 0, 0, 0) + format(1)
        val descriptors = UsbAudioPcmDescriptors(input)
        assertEquals(8, descriptors.clockSource(descriptors.formats.single()) { control, selector ->
            assertEquals(3, control); assertEquals(9, selector); 2
        })
        assertNull(descriptors.clockSource(descriptors.formats.single()) { _, _ -> 3 })
    }

    @Test fun truncatedDescriptorDoesNotInventAProfile() {
        val raw = control + format(1)
        assertTrue(UsbAudioPcmDescriptors(raw.copyOf(raw.size - 1)).formats.isEmpty())
    }

    @Test fun discreteDacRatesDoNotInvent176400Between96000And192000() {
        val rates = listOf(44100, 48000, 96000, 192000, 384000)
        val buffer = ByteBuffer.allocate(2 + rates.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(rates.size.toShort())
        rates.forEach { buffer.putInt(it).putInt(it).putInt(0) }
        val ranges = usbPcmRateRanges(buffer.array())!!
        assertTrue(ranges.any { it.contains(192000) })
        assertFalse(ranges.any { it.contains(176400) })
        assertFalse(ranges.any { it.contains(88200) })
        assertNull(usbPcmRateRanges(buffer.array().copyOf(8)))
        assertTrue(UsbRateRange(48000, 192000, 48000).contains(96000))
        assertFalse(UsbRateRange(48000, 192000, 48000).contains(88200))
    }
}
