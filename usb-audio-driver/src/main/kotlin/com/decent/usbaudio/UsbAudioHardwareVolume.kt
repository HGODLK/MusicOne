package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** UAC2 控制传输接口，方便单元测试在不接真机时模拟硬件响应。 */
internal fun interface UsbControlTransfer {
    fun transfer(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray): Int
}

/**
 * 独占输出时管理播放链路上的 UAC2 硬件音量。默认推到最大并解除静音，音量大小由软件增益负责；
 * 开启硬件音量后，选定一个经回读确认生效的功能单元按系统音量写入衰减，软件不再改写 PCM。
 * 部分 DAC 上电默认硬件音量偏低，系统通路会由主机设置，独占后无人设置就会推力不足。
 * 首次改写前记录原值，关闭设备时写回，避免影响系统 USB 音频通路。
 */
internal class UsbAudioHardwareVolume(
    private val controlTransfer: UsbControlTransfer,
    private val logInfo: (String) -> Unit = { Log.i(TAG, it) },
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) },
) {
    constructor(connection: UsbDeviceConnection) : this(UsbControlTransfer { type, request, value, index, buffer ->
        connection.controlTransfer(type, request, value, index, buffer, buffer.size, 1000)
    })

    private data class Control(val unit: UsbFeatureUnit, val selector: Int, val channel: Int)

    private val originals = linkedMapOf<Control, ByteArray>()
    private val written = hashMapOf<Control, Int>()
    // 衰减用的音量控件及其范围；为空表示设备没有可写的硬件音量。
    private var attenuation: List<Pair<Control, UsbVolumeRange>> = emptyList()
    private var muteControls: List<Control> = emptyList()
    private var supportsReadback = false

    @Synchronized fun maximize(units: List<UsbFeatureUnit>) {
        units.forEach { maximizeUnit(it) }
    }

    /**
     * 依次探测播放链路上的功能单元与声道组（优先主声道 0，假响应或失败时自动回退子声道及下一个单元），
     * 选中真正经回读验证可调的一组并直接写入 [initialGain]，其余单元与非衰减声道保持推满。
     * 返回实际生效的线性增益；均无效时退回全部推满并返回 null。
     */
    @Synchronized fun prepareAttenuation(units: List<UsbFeatureUnit>, initialGain: Float = 1f): Float? {
        if (attenuation.isNotEmpty()) return attenuate(initialGain)
        units.forEach { unit ->
            fun usableControls(channels: List<Int>) = channels.mapNotNull { channel ->
                val control = Control(unit, VOLUME_CONTROL, channel)
                volumeRange(control)?.takeIf { it.max > it.min }?.let { control to it }
            }
            val candidateGroups = buildList {
                val master = unit.channelControls.firstOrNull()
                if (master != null && writable(master, VOLUME_SHIFT)) {
                    val masterControls = usableControls(listOf(0))
                    if (masterControls.isNotEmpty()) add(masterControls)
                }
                val subChannels = unit.channelControls.indices.drop(1)
                    .filter { writable(unit.channelControls[it], VOLUME_SHIFT) }
                if (subChannels.isNotEmpty()) {
                    val subControls = usableControls(subChannels)
                    if (subControls.isNotEmpty()) add(subControls)
                }
            }
            for (controls in candidateGroups) {
                val activeChannels = controls.map { it.first.channel }.toSet()
                val mutes = (listOf(0) + activeChannels).distinct()
                    .filter { unit.channelControls.getOrNull(it)?.let { bits -> writable(bits, MUTE_SHIFT) } == true }
                    .map { Control(unit, MUTE_CONTROL, it) }
                val applied = probeAndApply(controls, mutes, initialGain)
                if (applied == null) {
                    // 当前声道组无效，复位推满后再试下一组，避免残留衰减叠加到后续声道。
                    controls.forEach { (control, range) -> write(control, range.max, 2) }
                    continue
                }
                val muteChannels = mutes.map { it.channel }.toSet()
                units.forEach { other ->
                    if (other == unit) maximizeUnit(other, skipVolumeChannels = activeChannels, skipMuteChannels = muteChannels)
                    else maximizeUnit(other)
                }
                logInfo("硬件音量：FU ${unit.unitId} 声道=${activeChannels.toList()} 范围=${controls.first().second} 回读=$supportsReadback")
                return applied
            }
        }
        maximize(units)
        return null
    }

    /**
     * 写入并校验候选声道组是否真正生效：
     * 支持 GET_CUR 的设备必须回读确认硬件值确实随 SET_CUR 改变，过滤“假 ACK”不响应的声道或单元。
     */
    private fun probeAndApply(
        controls: List<Pair<Control, UsbVolumeRange>>,
        mutes: List<Control>,
        initialGain: Float,
    ): Float? {
        val (leadControl, leadRange) = controls.first()
        val before = getCurSigned16(leadControl)
        val desired = if (initialGain <= 0f) leadRange.min else usbAttenuationValue(initialGain, leadRange)
        val tolerance = maxOf(leadRange.res, READBACK_TOLERANCE_DB_UNITS)

        attenuation = controls
        muteControls = mutes
        supportsReadback = before != null

        if (before != null) {
            val probeValue = selectProbeValue(before, desired, leadRange)
            if (probeValue != null) {
                // 目标值与当前值过近时，先做一次明显偏移试探，再立刻写回正式目标值。
                if (!write(leadControl, probeValue, 2)) return clearCandidate()
                val probed = getCurSigned16(leadControl)
                if (probed != null && !readbackAccepted(before, probeValue, probed, tolerance)) {
                    logWarn("FU ${leadControl.unit.unitId} ch=${leadControl.channel} 试探未生效：before=$before probe=$probeValue after=$probed")
                    return clearCandidate()
                }
            }
        }

        val applied = attenuate(initialGain)
        if (applied == null || written[leadControl] != desired) return clearCandidate()

        if (before != null && abs(desired - before) >= tolerance) {
            val after = getCurSigned16(leadControl)
            if (after != null && !readbackAccepted(before, desired, after, tolerance)) {
                logWarn("FU ${leadControl.unit.unitId} ch=${leadControl.channel} 写入未生效：before=$before target=$desired after=$after")
                return clearCandidate()
            }
        }
        return applied
    }

    private fun clearCandidate(): Float? {
        attenuation = emptyList()
        muteControls = emptyList()
        supportsReadback = false
        return null
    }

    private fun selectProbeValue(before: Int, desired: Int, range: UsbVolumeRange): Int? {
        val minDelta = maxOf(range.res, READBACK_TOLERANCE_DB_UNITS)
        if (abs(desired - before) >= minDelta) return null
        val step = maxOf(range.res, PROBE_STEP_DB_UNITS)
        return when {
            before - step >= range.min -> before - step
            before + step <= range.max -> before + step
            before > range.min -> range.min
            else -> range.max
        }.takeIf { it != before }
    }

    private fun readbackAccepted(before: Int, expected: Int, actual: Int, tolerance: Int): Boolean =
        actual != before && abs(actual - expected) <= tolerance

    private fun maximizeUnit(
        unit: UsbFeatureUnit,
        skipVolumeChannels: Set<Int> = emptySet(),
        skipMuteChannels: Set<Int> = emptySet(),
    ) {
        unit.channelControls.forEachIndexed { channel, controls ->
            if (channel !in skipMuteChannels && writable(controls, MUTE_SHIFT)) {
                write(Control(unit, MUTE_CONTROL, channel), 0, 1)
            }
            if (channel !in skipVolumeChannels && writable(controls, VOLUME_SHIFT)) {
                val control = Control(unit, VOLUME_CONTROL, channel)
                val maximum = volumeRange(control)?.max ?: return@forEachIndexed
                write(control, maximum, 2)
            }
        }
    }

    /** 按线性增益写入硬件衰减（0 为静音），返回实际生效的线性增益；没有可用控件时返回 null。 */
    @Synchronized fun attenuate(gain: Float): Float? {
        if (attenuation.isEmpty()) return null
        val mute = gain <= 0f
        // 静音时先压到最低再静音，取消静音时先写好音量再放开，避免切换瞬间漏出大声。
        if (!mute) attenuation.forEach { (control, range) -> write(control, usbAttenuationValue(gain, range), 2) }
        else attenuation.forEach { (control, range) -> write(control, range.min, 2) }
        muteControls.forEach { write(it, if (mute) 1 else 0, 1) }
        if (mute) return 0f
        val (control, range) = attenuation.first()
        val actual = if (supportsReadback) getCurSigned16(control) ?: written[control] ?: range.max
            else written[control] ?: range.max
        return usbAttenuationGain(actual, range)
    }

    /** 按改写的相反顺序恢复，先恢复音量再恢复静音状态；之后不再写入。 */
    @Synchronized fun restore() {
        attenuation = emptyList()
        muteControls = emptyList()
        supportsReadback = false
        originals.entries.reversed().forEach { (control, value) -> setCur(control, value) }
        originals.clear()
        written.clear()
    }

    private fun write(control: Control, value: Int, size: Int): Boolean {
        if (written[control] == value) return true
        if (control !in originals) {
            val current = ByteArray(size)
            if (getCur(control, current)) originals[control] = current
        }
        val bytes = if (size == 1) byteArrayOf(value.toByte()) else byteArrayOf(value.toByte(), (value shr 8).toByte())
        val ok = setCur(control, bytes)
        if (ok) written[control] = value
        logInfo("FU ${control.unit.unitId} ch=${control.channel} cs=${control.selector} set=$value ok=$ok")
        return ok
    }

    /** UAC2 二字节 RANGE：数量后跟若干 MIN/MAX/RES，单位 1/256 dB。 */
    private fun volumeRange(control: Control): UsbVolumeRange? {
        val header = ByteArray(2)
        if (transfer(0xa1, RANGE, control, header) != 2) return null
        val count = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
        if (count !in 1..64) return null
        val data = ByteArray(2 + count * 6)
        if (transfer(0xa1, RANGE, control, data) != data.size) return null
        return usbVolumeRange(data)
    }

    private fun getCurSigned16(control: Control): Int? {
        val data = ByteArray(2)
        if (!getCur(control, data)) return null
        return ((data[0].toInt() and 255) or (data[1].toInt() shl 8)).toShort().toInt()
    }

    private fun getCur(control: Control, buffer: ByteArray) = transfer(0xa1, CUR, control, buffer) == buffer.size
    private fun setCur(control: Control, value: ByteArray) = transfer(0x21, CUR, control, value) == value.size

    private fun transfer(type: Int, request: Int, control: Control, buffer: ByteArray): Int =
        controlTransfer.transfer(type, request, (control.selector shl 8) or control.channel,
            control.unit.controlInterface or (control.unit.unitId shl 8), buffer)

    private fun writable(controls: Int, shift: Int) = (controls shr shift) and 3 == 3

    private companion object {
        const val TAG = "UsbAudioDevice"
        const val CUR = 1
        const val RANGE = 2
        const val MUTE_CONTROL = 1
        const val VOLUME_CONTROL = 2
        const val MUTE_SHIFT = 0
        const val VOLUME_SHIFT = 2
        // 回读容差：2dB（兼容内部步长比声明更粗的 DAC）；试探步长：6dB（避开粗量化误判）。
        const val READBACK_TOLERANCE_DB_UNITS = 2 * 256
        const val PROBE_STEP_DB_UNITS = 6 * 256
    }
}

/** 硬件音量范围（有符号 1/256 dB），res 为调节步长。 */
internal data class UsbVolumeRange(val min: Int, val max: Int, val res: Int)

/** 解析二字节 RANGE：忽略纯静音点，兼容 0x8000 下限并取首个有效步长；数据不完整时返回 null。 */
internal fun usbVolumeRange(data: ByteArray): UsbVolumeRange? {
    if (data.size < 2) return null
    val count = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
    if (count == 0 || data.size < 2 + count * 6) return null
    fun signed(offset: Int) = ((data[offset].toInt() and 255) or (data[offset + 1].toInt() shl 8)).toShort().toInt()
    fun unsigned(offset: Int) = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    val entries = (0 until count).map {
        val offset = 2 + it * 6
        UsbVolumeRange(signed(offset), signed(offset + 2), unsigned(offset + 4))
    }
    // 0x8000 表示负无穷（静音）；排除纯静音点后至少需要一个有效上限。
    val finite = entries.filter { it.max != Short.MIN_VALUE.toInt() }
    if (finite.isEmpty()) return null
    val maximum = finite.maxOf { it.max }
    val resolution = finite.firstOrNull { it.res > 0 }?.res ?: 1
    val explicitMinimum = finite.map { it.min }.filter { it != Short.MIN_VALUE.toInt() }.minOrNull()
    val minimum = explicitMinimum ?: run {
        // 子范围下限均为 0x8000 时，从上限向下按步长推算合法有限下限（不低于 -127.996dB）。
        val floor = maxOf(Short.MIN_VALUE.toInt() + 1, maximum - 127 * 256)
        if (floor >= maximum) maximum else maximum - ((maximum - floor) / resolution) * resolution
    }
    if (minimum > maximum) return null
    return UsbVolumeRange(minimum, maximum, resolution)
}

/** 解析二字节 RANGE 的最大值（有符号 1/256 dB）；数据不完整时返回 null。 */
internal fun usbVolumeRangeMaximum(data: ByteArray): Int? = usbVolumeRange(data)?.max

/** 线性增益换成相对最大值的硬件音量值，对齐步长并限制在范围内。 */
internal fun usbAttenuationValue(gain: Float, range: UsbVolumeRange): Int {
    if (gain >= 1f) return range.max
    val decibels = 20.0 * log10(gain.toDouble().coerceAtLeast(1e-9))
    val steps = (decibels * 256.0 / range.res).roundToInt()
    return (range.max + steps * range.res).coerceIn(range.min, range.max)
}

/** 硬件音量值对应的线性增益（相对最大值）。 */
internal fun usbAttenuationGain(value: Int, range: UsbVolumeRange): Float =
    10.0.pow((value - range.max) / 256.0 / 20.0).toFloat().coerceIn(0f, 1f)
