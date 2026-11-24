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

    @Test fun onlyFeatureUnitsFedByThePlaybackTerminalAreSelected() {
        val playbackUnit = bytes(18, 0x24, 6, 2, 1, 0x0f, 0, 0, 0, 0x0c, 0, 0, 0, 0x0c, 0, 0, 0, 0)
        val output = bytes(12, 0x24, 3, 3, 1, 3, 0, 2, 7, 0, 0, 0)
        val recordInput = bytes(17, 0x24, 2, 4, 1, 2, 0, 7, 2, 3, 0, 0, 0, 0, 0, 0, 0)
        val recordUnit = bytes(14, 0x24, 6, 5, 4, 0x0f, 0, 0, 0, 0x0c, 0, 0, 0, 0)
        val descriptors = UsbAudioPcmDescriptors(control + playbackUnit + output + recordInput + recordUnit + format(1))
        val units = descriptors.playbackFeatureUnits(descriptors.formats.single())
        assertEquals(listOf(2), units.map { it.unitId })
        assertEquals(listOf(0x0f, 0x0c, 0x0c), units.single().channelControls)
    }

    @Test fun volumeRangeMaximumIsSignedAcrossSubRanges() {
        val buffer = ByteBuffer.allocate(2 + 2 * 6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(2)
        buffer.putShort((-127 * 256).toShort()).putShort((-20 * 256).toShort()).putShort(256)
        buffer.putShort((-20 * 256).toShort()).putShort(0).putShort(128)
        assertEquals(0, usbVolumeRangeMaximum(buffer.array()))
        assertNull(usbVolumeRangeMaximum(buffer.array().copyOf(10)))
        assertEquals(UsbVolumeRange(-127 * 256, 0, 256), usbVolumeRange(buffer.array()))
    }

    @Test fun volumeRangeSupportsMuteFloorAndSkipsLeadingSilencePoint() {
        // 单段下限为 0x8000（负无穷静音）时不应误判为不支持。
        val singleMuteFloor = ByteBuffer.allocate(2 + 6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(Short.MIN_VALUE).putShort(0).putShort(256)
            .array()
        assertEquals(UsbVolumeRange(-127 * 256, 0, 256), usbVolumeRange(singleMuteFloor))

        // 首段为纯静音点（res=0）时，步长与范围应取后续有效段。
        val leadingSilence = ByteBuffer.allocate(2 + 2 * 6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2)
            .putShort(Short.MIN_VALUE).putShort(Short.MIN_VALUE).putShort(0)
            .putShort((-60 * 256).toShort()).putShort(0).putShort(128)
            .array()
        assertEquals(UsbVolumeRange(-60 * 256, 0, 128), usbVolumeRange(leadingSilence))

        // 仅含静音点时无可用范围。
        val onlySilence = ByteBuffer.allocate(2 + 6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(Short.MIN_VALUE).putShort(Short.MIN_VALUE).putShort(0)
            .array()
        assertNull(usbVolumeRange(onlySilence))
    }

    @Test fun attenuationFollowsDecibelsAlignedToStepsWithinRange() {
        val range = UsbVolumeRange(-60 * 256, 0, 256)
        assertEquals(0, usbAttenuationValue(1f, range))
        // 0.5 ≈ -6.02dB，对齐 1dB 步长后为 -6dB。
        assertEquals(-6 * 256, usbAttenuationValue(.5f, range))
        // 超出设备下限时停在最低档，由软件补足剩余衰减。
        assertEquals(-60 * 256, usbAttenuationValue(.0001f, range))
        assertEquals(.501f, usbAttenuationGain(-6 * 256, range), .001f)
        assertEquals(1f, usbAttenuationGain(0, range), 1e-6f)
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
