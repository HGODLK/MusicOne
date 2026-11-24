package com.decent.usbaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbAudioHardwareVolumeTest {
    private class FakeUac2Mixer(
        private val ranges: Map<Pair<Int, Int>, UsbVolumeRange>,
        initialVolumes: Map<Pair<Int, Int>, Int>,
        private val ignoredWrites: Set<Pair<Int, Int>> = emptySet(),
        private val quantizeStep: Int? = null,
    ) : UsbControlTransfer {
        val volumes = initialVolumes.toMutableMap()
        val mutes = hashMapOf<Pair<Int, Int>, Int>()

        override fun transfer(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray): Int {
            val selector = (value ushr 8) and 0xFF
            val channel = value and 0xFF
            val unitId = (index ushr 8) and 0xFF
            val key = unitId to channel
            if (type == 0xa1 && request == 2 && selector == 2) {
                val range = ranges[key] ?: return -1
                val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(1)
                    .putShort(range.min.toShort())
                    .putShort(range.max.toShort())
                    .putShort(range.res.toShort())
                    .array()
                val size = minOf(buffer.size, payload.size)
                payload.copyInto(buffer, endIndex = size)
                return size
            }
            if (type == 0xa1 && request == 1 && selector == 2 && buffer.size == 2) {
                val current = volumes[key] ?: return -1
                buffer[0] = current.toByte()
                buffer[1] = (current shr 8).toByte()
                return 2
            }
            if (type == 0x21 && request == 1 && selector == 2 && buffer.size == 2) {
                if (key !in ranges) return -1
                if (key !in ignoredWrites) {
                    val requested = ((buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)).toShort().toInt()
                    val step = quantizeStep
                    volumes[key] = if (step != null) (requested / step) * step else requested
                }
                // 模拟固件对无效通道依然回 ACK。
                return 2
            }
            if (type == 0xa1 && request == 1 && selector == 1 && buffer.size == 1) {
                buffer[0] = (mutes[key] ?: 0).toByte()
                return 1
            }
            if (type == 0x21 && request == 1 && selector == 1 && buffer.size == 1) {
                mutes[key] = buffer[0].toInt() and 0xFF
                return 1
            }
            return -1
        }
    }

    @Test fun masterChannelFakeAckFallsBackToSubChannels() {
        val range = UsbVolumeRange(-60 * 256, 0, 256)
        val unit = UsbFeatureUnit(0, 2, listOf(0x0f, 0x0c, 0x0c))
        val mixer = FakeUac2Mixer(
            ranges = mapOf((2 to 0) to range, (2 to 1) to range, (2 to 2) to range),
            initialVolumes = mapOf((2 to 0) to 0, (2 to 1) to 0, (2 to 2) to 0),
            ignoredWrites = setOf(2 to 0),
        )
        val hardware = UsbAudioHardwareVolume(mixer, logInfo = {}, logWarn = {})
        val applied = hardware.prepareAttenuation(listOf(unit), initialGain = .25f)
        assertNotNull(applied)
        assertEquals(.25f, applied!!, .01f)
        // 主声道假 ACK 被识别后，自动改写左右声道。
        assertEquals(-12 * 256, mixer.volumes[2 to 1])
        assertEquals(-12 * 256, mixer.volumes[2 to 2])

        val updated = hardware.attenuate(.5f)
        assertEquals(.501f, updated!!, .01f)
        assertEquals(-6 * 256, mixer.volumes[2 to 1])
        assertEquals(-6 * 256, mixer.volumes[2 to 2])
    }

    @Test fun firstFeatureUnitFakeAckFallsBackToNextUnit() {
        val range = UsbVolumeRange(-60 * 256, 0, 256)
        val dummyUnit = UsbFeatureUnit(0, 2, listOf(0x0f))
        val realUnit = UsbFeatureUnit(0, 5, listOf(0x0f))
        val mixer = FakeUac2Mixer(
            ranges = mapOf((2 to 0) to range, (5 to 0) to range),
            initialVolumes = mapOf((2 to 0) to 0, (5 to 0) to -20 * 256),
            ignoredWrites = setOf(2 to 0),
        )
        val hardware = UsbAudioHardwareVolume(mixer, logInfo = {}, logWarn = {})
        val applied = hardware.prepareAttenuation(listOf(dummyUnit, realUnit), initialGain = .5f)
        assertNotNull(applied)
        assertEquals(.501f, applied!!, .01f)
        assertEquals(-6 * 256, mixer.volumes[5 to 0])
    }

    @Test fun unityInitialGainStillDetectsFakeAckViaProbeAndFallsBackToSoftware() {
        val range = UsbVolumeRange(-60 * 256, 0, 256)
        val unit = UsbFeatureUnit(0, 2, listOf(0x0f))
        val mixer = FakeUac2Mixer(
            ranges = mapOf((2 to 0) to range),
            initialVolumes = mapOf((2 to 0) to 0),
            ignoredWrites = setOf(2 to 0),
        )
        val hardware = UsbAudioHardwareVolume(mixer, logInfo = {}, logWarn = {})
        assertNull(hardware.prepareAttenuation(listOf(unit), initialGain = 1f))
    }

    @Test fun attenuateReportsActualReadbackValueWhenHardwareQuantizesCoarsely() {
        val range = UsbVolumeRange(-60 * 256, 0, 256)
        val unit = UsbFeatureUnit(0, 2, listOf(0x0f))
        val mixer = FakeUac2Mixer(
            ranges = mapOf((2 to 0) to range),
            initialVolumes = mapOf((2 to 0) to 0),
            quantizeStep = 3 * 256,
        )
        val hardware = UsbAudioHardwareVolume(mixer, logInfo = {}, logWarn = {})
        val applied = hardware.prepareAttenuation(listOf(unit), initialGain = .25f)
        assertNotNull(applied)
        // 请求约 -10dB（0.316），硬件实际量化为 -9dB（0.355），回读应如实反映 -9dB 供软件补齐。
        val actual = hardware.attenuate(.316f)!!
        assertEquals(usbAttenuationGain(-9 * 256, range), actual, 1e-4f)
    }
}
