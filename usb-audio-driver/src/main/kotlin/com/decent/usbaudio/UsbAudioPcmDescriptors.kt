package com.decent.usbaudio

internal data class UsbPcmDescriptor(
    val controlInterface: Int, val interfaceId: Int, val altSetting: Int,
    val terminal: Int, val channels: Int, val bits: Int, val subslot: Int,
)

internal data class UsbClockDescriptor(val controls: Int)
internal data class UsbRateRange(val min: Int, val max: Int, val step: Int) {
    fun contains(rate: Int): Boolean = rate in min..max &&
        (if (step == 0) rate == min else (rate.toLong() - min) % step == 0L)
}

/** 仅接收 UAC2 的整数 PCM，避免把相同位深的浮点或 RAW 配置当作 PCM。 */
internal class UsbAudioPcmDescriptors(raw: ByteArray) {
    val formats = mutableListOf<UsbPcmDescriptor>()
    val clocks = mutableMapOf<Pair<Int, Int>, UsbClockDescriptor>()
    private val terminals = mutableMapOf<Pair<Int, Int>, Int>()
    private val selectors = mutableMapOf<Pair<Int, Int>, List<Int>>()

    init {
        var control = -1
        var iface = -1
        var alt = 0
        var subclass = 0
        var terminal = -1
        var channels = 0
        var pcm = false
        var offset = 0
        while (offset + 2 <= raw.size) {
            val size = raw[offset].toInt() and 255
            if (size < 2 || offset + size > raw.size) break
            fun u(index: Int) = raw[offset + index].toInt() and 255
            if (u(1) == 4 && size >= 9) {
                iface = u(2); alt = u(3)
                subclass = if (u(5) == 1 && u(7) == 0x20) u(6) else 0
                if (subclass == 1) control = iface
                terminal = -1; channels = 0; pcm = false
            } else if (u(1) == 0x24 && size >= 3 && control >= 0) {
                when (subclass) {
                    1 -> when (u(2)) {
                        2 -> if (size >= 17) terminals[control to u(3)] = u(7)
                        3 -> if (size >= 12) terminals[control to u(3)] = u(8)
                        0x0a -> if (size >= 8) clocks[control to u(3)] = UsbClockDescriptor(u(5))
                        0x0b -> if (size >= 7 && size >= 7 + u(4))
                            selectors[control to u(3)] = (0 until u(4)).map { u(5 + it) }
                    }
                    2 -> when (u(2)) {
                        1 -> if (size >= 16) {
                            terminal = u(3); channels = u(10)
                            val formats = u(6) or (u(7) shl 8) or (u(8) shl 16) or (u(9) shl 24)
                            pcm = u(5) == 1 && formats == 1
                        }
                        2 -> if (size >= 6 && u(3) == 1 && pcm && alt > 0 && channels > 0 &&
                            u(4) in 2..4 && u(5) in setOf(16, 24, 32) && u(5) <= u(4) * 8) {
                            formats += UsbPcmDescriptor(control, iface, alt, terminal, channels, u(5), u(4))
                        }
                    }
                }
            }
            offset += size
        }
    }

    fun clockSource(format: UsbPcmDescriptor, selectedPin: (Int, Int) -> Int): Int? {
        var id = terminals[format.controlInterface to format.terminal] ?: return null
        val visited = hashSetOf<Int>()
        while (visited.add(id)) {
            if (format.controlInterface to id in clocks) return id
            val inputs = selectors[format.controlInterface to id] ?: return null
            val pin = if (inputs.size == 1) 1 else selectedPin(format.controlInterface, id)
            id = inputs.getOrNull(pin - 1) ?: return null
        }
        return null
    }
}

/** UAC2 RANGE 是数量加若干 MIN/MAX/RES 三元组，采样率可能是离散值。 */
internal fun usbPcmRateRanges(bytes: ByteArray): List<UsbRateRange>? {
    if (bytes.size < 2) return null
    val count = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
    if (count == 0 || bytes.size.toLong() < 2L + count * 12L) return null
    fun number(offset: Int): Int = (0..3).fold(0) { value, index ->
        value or ((bytes[offset + index].toInt() and 255) shl (index * 8))
    }
    return (0 until count).map { index ->
        val offset = 2 + index * 12
        UsbRateRange(number(offset), number(offset + 4), number(offset + 8))
    }.takeIf { ranges -> ranges.all { it.min > 0 && it.max >= it.min && it.step >= 0 &&
        (it.step > 0 || it.min == it.max) } }
}
