package com.decent.usbaudio

/**
 * USB HID Consumer Control（Usage Page 0x0C）音量键映射，由标准 HID Report Descriptor 解析得到。
 * 同时支持常见的 Variable 位图报告与 Array 键值数组报告。
 */
internal data class UsbHidVolumeMapping(
    val upBitFields: List<BitField> = emptyList(),
    val downBitFields: List<BitField> = emptyList(),
    val consumerArrays: List<ArrayField> = emptyList(),
) {
    val isSupported: Boolean
        get() = upBitFields.isNotEmpty() || downBitFields.isNotEmpty() || consumerArrays.isNotEmpty()

    /** 按位偏移描述的音量开关字段；bitOffset 不含首字节 Report ID。 */
    internal data class BitField(val reportId: Int?, val bitOffset: Int, val bitSize: Int)

    /** 存放 Consumer Control Usage ID（0xE9/0xEA）的定宽数组字段；bitOffset 不含首字节 Report ID。 */
    internal data class ArrayField(val reportId: Int?, val bitOffset: Int, val bitSize: Int, val count: Int)
}

/**
 * 解析 USB HID 1.11 Report Descriptor，提取 Consumer Control 的音量加（0xE9）与音量减（0xEA）字段位置。
 * 仅关注 Input 项，忽略填充位与厂商自定义 Usage Page，避免误把调参通道当作音量键。
 */
internal fun parseUsbHidVolumeDescriptor(raw: ByteArray): UsbHidVolumeMapping {
    data class GlobalState(
        val usagePage: Int = 0,
        val reportSize: Int = 0,
        val reportCount: Int = 0,
        val reportId: Int? = null,
    )

    var global = GlobalState()
    val stack = ArrayDeque<GlobalState>()
    val localUsages = mutableListOf<Int>()
    var usageMin: Int? = null
    var usageMax: Int? = null
    val reportBitOffsets = hashMapOf<Int?, Int>()
    val upBits = mutableListOf<UsbHidVolumeMapping.BitField>()
    val downBits = mutableListOf<UsbHidVolumeMapping.BitField>()
    val arrays = mutableListOf<UsbHidVolumeMapping.ArrayField>()

    fun fullUsage(value: Int, size: Int): Int =
        if (size == 4) value else ((global.usagePage and 0xFFFF) shl 16) or (value and 0xFFFF)

    var index = 0
    while (index < raw.size) {
        val prefix = raw[index].toInt() and 0xFF
        if (prefix == 0xFE) {
            // Long Item：首字节 0xFE，后跟 1 字节长度、1 字节标签及数据。
            if (index + 1 >= raw.size) break
            val length = raw[index + 1].toInt() and 0xFF
            index += 3 + length
            continue
        }
        val sizeCode = prefix and 0x03
        val dataSize = if (sizeCode == 3) 4 else sizeCode
        val itemType = (prefix ushr 2) and 0x03
        val tag = (prefix ushr 4) and 0x0F
        if (index + 1 + dataSize > raw.size) break

        var value = 0
        for (i in 0 until dataSize) {
            value = value or ((raw[index + 1 + i].toInt() and 0xFF) shl (i * 8))
        }
        index += 1 + dataSize

        when (itemType) {
            1 -> when (tag) {
                0x0 -> global = global.copy(usagePage = value)
                0x7 -> global = global.copy(reportSize = value)
                0x8 -> global = global.copy(reportId = value and 0xFF)
                0x9 -> global = global.copy(reportCount = value)
                0xA -> stack.addLast(global)
                0xB -> if (stack.isNotEmpty()) global = stack.removeLast()
            }
            2 -> when (tag) {
                0x0 -> localUsages += fullUsage(value, dataSize)
                0x1 -> usageMin = fullUsage(value, dataSize)
                0x2 -> usageMax = fullUsage(value, dataSize)
            }
            0 -> {
                if (tag == 0x8 && global.reportSize > 0 && global.reportCount > 0) {
                    val reportId = global.reportId
                    val startBit = reportBitOffsets[reportId] ?: 0
                    val isConstant = (value and 0x01) != 0
                    val isVariable = (value and 0x02) != 0
                    if (!isConstant) {
                        if (isVariable) {
                            val expanded = expandUsages(localUsages, usageMin, usageMax, global.reportCount)
                            for (slot in 0 until global.reportCount) {
                                val usage = expanded.getOrNull(slot) ?: break
                                val field = UsbHidVolumeMapping.BitField(
                                    reportId = reportId,
                                    bitOffset = startBit + slot * global.reportSize,
                                    bitSize = global.reportSize,
                                )
                                when (usage) {
                                    CONSUMER_VOLUME_UP -> upBits += field
                                    CONSUMER_VOLUME_DOWN -> downBits += field
                                }
                            }
                        } else if (global.reportSize == 8 || global.reportSize == 16) {
                            val coversVolume = coversConsumerVolume(global.usagePage, localUsages, usageMin, usageMax)
                            if (coversVolume) {
                                arrays += UsbHidVolumeMapping.ArrayField(
                                    reportId = reportId,
                                    bitOffset = startBit,
                                    bitSize = global.reportSize,
                                    count = global.reportCount,
                                )
                            }
                        }
                    }
                    reportBitOffsets[reportId] = startBit + global.reportSize * global.reportCount
                }
                // 每个 Main Item 结束后清空 Local 状态。
                localUsages.clear()
                usageMin = null
                usageMax = null
            }
        }
    }
    return UsbHidVolumeMapping(upBits, downBits, arrays)
}

private fun expandUsages(
    localUsages: List<Int>,
    usageMin: Int?,
    usageMax: Int?,
    count: Int,
): List<Int> {
    if (usageMin != null && usageMax != null && usageMax >= usageMin) {
        val result = ArrayList<Int>(count)
        for (i in 0 until count) {
            result += (usageMin + i).coerceAtMost(usageMax)
        }
        return result
    }
    if (localUsages.isEmpty()) return emptyList()
    return List(count) { i -> localUsages[i.coerceAtMost(localUsages.lastIndex)] }
}

private fun coversConsumerVolume(
    usagePage: Int,
    localUsages: List<Int>,
    usageMin: Int?,
    usageMax: Int?,
): Boolean {
    if (usagePage == USAGE_PAGE_CONSUMER) {
        if (usageMin != null && usageMax != null &&
            (CONSUMER_VOLUME_UP in usageMin..usageMax || CONSUMER_VOLUME_DOWN in usageMin..usageMax)
        ) {
            return true
        }
    }
    return localUsages.any { it == CONSUMER_VOLUME_UP || it == CONSUMER_VOLUME_DOWN }
}

/**
 * 解码单条 HID 输入报告并做按下边沿检测（按住松开不重复触发，返回 +1 为音量加、-1 为音量减）。
 */
internal class UsbHidVolumeReportDecoder(private val mapping: UsbHidVolumeMapping) {
    private var upPressed = false
    private var downPressed = false

    fun decode(report: ByteArray, length: Int): List<Int> {
        if (length <= 0 || !mapping.isSupported) return emptyList()
        var nextUp = false
        var nextDown = false
        var matched = false

        for (field in mapping.upBitFields) {
            val payloadOffset = matchReportOffset(field.reportId, report, length) ?: continue
            matched = true
            if (readUnsignedBits(report, length, payloadOffset, field.bitOffset, field.bitSize) != 0) nextUp = true
        }
        for (field in mapping.downBitFields) {
            val payloadOffset = matchReportOffset(field.reportId, report, length) ?: continue
            matched = true
            if (readUnsignedBits(report, length, payloadOffset, field.bitOffset, field.bitSize) != 0) nextDown = true
        }
        for (array in mapping.consumerArrays) {
            val payloadOffset = matchReportOffset(array.reportId, report, length) ?: continue
            matched = true
            for (slot in 0 until array.count) {
                val usageId = readUnsignedBits(
                    report, length, payloadOffset, array.bitOffset + slot * array.bitSize, array.bitSize,
                )
                if (usageId == USAGE_VOLUME_UP) nextUp = true
                if (usageId == USAGE_VOLUME_DOWN) nextDown = true
            }
        }
        if (!matched) return emptyList()

        val events = mutableListOf<Int>()
        if (nextUp && !upPressed) events += 1
        if (nextDown && !downPressed) events += -1
        upPressed = nextUp
        downPressed = nextDown
        return events
    }

    private fun matchReportOffset(reportId: Int?, report: ByteArray, length: Int): Int? {
        if (reportId == null) return 0
        if (length < 2) return null
        return if ((report[0].toInt() and 0xFF) == reportId) 1 else null
    }

    private fun readUnsignedBits(
        data: ByteArray,
        length: Int,
        payloadByteOffset: Int,
        bitOffset: Int,
        bitSize: Int,
    ): Int {
        val clampedSize = bitSize.coerceIn(1, 31)
        var result = 0
        for (bit in 0 until clampedSize) {
            val totalBit = payloadByteOffset * 8 + bitOffset + bit
            val byteIndex = totalBit ushr 3
            if (byteIndex >= length) return 0
            val bitInByte = totalBit and 7
            if (((data[byteIndex].toInt() ushr bitInByte) and 1) != 0) {
                result = result or (1 shl bit)
            }
        }
        return result
    }
}

private const val USAGE_PAGE_CONSUMER = 0x0C
private const val USAGE_VOLUME_UP = 0xE9
private const val USAGE_VOLUME_DOWN = 0xEA
private const val CONSUMER_VOLUME_UP = (USAGE_PAGE_CONSUMER shl 16) or USAGE_VOLUME_UP
private const val CONSUMER_VOLUME_DOWN = (USAGE_PAGE_CONSUMER shl 16) or USAGE_VOLUME_DOWN
