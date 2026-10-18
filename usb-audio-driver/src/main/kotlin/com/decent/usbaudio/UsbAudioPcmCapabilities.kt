package com.decent.usbaudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlin.math.ceil

/** 能力、实际端点和时钟控制绑定到同一配置，不以最高采样率推断中间档位。 */
internal class UsbAudioPcmCapabilities(private val connection: UsbDeviceConnection, private val device: UsbDevice,
    private val streamingInterfaceId: Int) {
    val descriptors = UsbAudioPcmDescriptors(connection.rawDescriptors ?: byteArrayOf())
    private val rates = mutableMapOf<Pair<Int, Int>, List<UsbRateRange>?>()
    private val speed = UsbAudioStream.nativeGetSpeed(connection.fileDescriptor)
    private val busIntervals = if (speed >= 3) 8000 else if (speed == 2) 1000 else 0

    fun profile(rate: Int, channels: Int, minimumBits: Int): UsbAudioStreamProfile? {
        if (busIntervals == 0) return null
        for (format in descriptors.formats.filter { it.interfaceId == streamingInterfaceId && it.channels == channels && it.bits >= minimumBits }
            .sortedWith(compareBy({ it.bits }, { it.subslot }))) {
            val clock = descriptors.clockSource(format, ::selectorPin) ?: continue
            val key = format.controlInterface to clock
            val supported = if (rates.containsKey(key)) rates[key] else readRanges(key).also { rates[key] = it }
            if (supported != null && supported.none { it.contains(rate) }) continue
            val iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.id == format.interfaceId && it.alternateSetting == format.altSetting
            } ?: continue
            val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            val out = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                it.direction == UsbConstants.USB_DIR_OUT } ?: continue
            if (out.interval !in 1..16) continue
            val packetsPerSecond = busIntervals / (1 shl (out.interval - 1))
            if (packetsPerSecond <= 0) continue
            val maxPacket = (out.maxPacketSize and 0x7ff) * (1 + ((out.maxPacketSize shr 11) and 3))
            val async = ((out.attributes shr 2) and 3) == 1
            val required = ceil(rate.toDouble() / packetsPerSecond * if (async) 1.01 else 1.0).toInt() * channels * format.subslot
            if (maxPacket < required || required * (packetsPerSecond / 1000).coerceIn(1, 8) > 4096) continue
            val feedback = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                it.direction == UsbConstants.USB_DIR_IN && ((it.attributes shr 4) and 3) == 1 }
            // 异步 OUT 必须有显式反馈，本实现不猜测其他接口的隐式反馈。
            if (async && feedback == null) continue
            if (feedback != null && (feedback.maxPacketSize and 0x7ff) < if (busIntervals == 1000) 3 else 4) continue
            val controls = descriptors.clocks[key]?.controls ?: continue
            Log.i("UsbAudioDevice", "PCM profile rate=$rate bits=${format.bits} ch=$channels alt=${format.altSetting} clock=$clock")
            return UsbAudioStreamProfile(format.interfaceId, format.altSetting, format.bits, format.subslot,
                out.address, feedback?.address ?: -1, maxPacket, packetsPerSecond, busIntervals,
                format.controlInterface, clock, (controls shr 2) and 1 != 0)
        }
        return null
    }

    private fun selectorPin(control: Int, clock: Int): Int {
        val data = ByteArray(1)
        return if (connection.controlTransfer(0xa1, 1, 0x0100, control or (clock shl 8), data, 1, 1000) == 1)
            data[0].toInt() and 255 else -1
    }

    private fun readRanges(key: Pair<Int, Int>): List<UsbRateRange>? {
        val header = ByteArray(2)
        val index = key.first or (key.second shl 8)
        if (connection.controlTransfer(0xa1, 2, 0x0100, index, header, 2, 1000) != 2) return null
        val count = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
        if (count !in 1..256) return null
        val data = ByteArray(2 + count * 12)
        if (connection.controlTransfer(0xa1, 2, 0x0100, index, data, data.size, 1000) != data.size) return null
        return usbPcmRateRanges(data)
    }

    fun setRate(profile: UsbAudioStreamProfile, rate: Int): Boolean {
        val data = ByteArray(4) { (rate shr (8 * it)).toByte() }
        return connection.controlTransfer(0x21, 1, 0x0100, index(profile), data, 4, 1000) == 4
    }

    fun readRate(profile: UsbAudioStreamProfile): Int {
        val data = ByteArray(4)
        if (connection.controlTransfer(0xa1, 1, 0x0100, index(profile), data, 4, 1000) != 4) return -1
        return (0..3).fold(0) { value, byte -> value or ((data[byte].toInt() and 255) shl (8 * byte)) }
    }

    fun clockValid(profile: UsbAudioStreamProfile): Boolean {
        if (!profile.clockValidityReadable) return true
        val data = ByteArray(1)
        return connection.controlTransfer(0xa1, 1, 0x0200, index(profile), data, 1, 100) == 1 && (data[0].toInt() and 1) == 1
    }

    private fun index(profile: UsbAudioStreamProfile) = profile.controlInterfaceId or (profile.clockSourceId shl 8)
}
