package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStreamProfile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 最近一次独占输出时设备是否支持硬件音量；null 表示尚未检测，供设置页说明。 */
internal object UsbHardwareVolumeStatus {
    var supported by mutableStateOf<Boolean?>(null)
}

/**
 * 让 USB 设备的硬件音量跟随系统媒体音量。控制传输会阻塞，写入放在单独线程，连续变化只写最后一次；
 * 记录硬件实际生效的增益，未跟上或到达设备下限的部分由软件增益补足（见 [usbSoftwareCompensation]）。
 * 请求 1 表示把硬件推满，关闭开关时据此退回纯软件音量。
 */
internal class UsbHardwareVolumeFollower(
    private val device: UsbAudioDevice,
    private val profile: UsbAudioStreamProfile,
    initialSupported: Boolean? = null,
    initialGain: Float = 1f,
    initialAppliedGain: Float = 1f,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "USB硬件音量").apply { isDaemon = true } }
    @Volatile private var requested = initialGain
    private var written = initialGain
    private val scheduled = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var supported: Boolean? = initialSupported
    /** 硬件当前实际衰减到的线性增益，1 为推满。 */
    @Volatile var appliedGain = initialAppliedGain
        private set

    /**
     * 跟随目标线性增益。
     * [probe] 为 true 且尚未检测过设备能力时，即使目标与当前一致也会探测一次，保证设置页状态立即更新。
     */
    fun follow(gain: Float, probe: Boolean = true) {
        if (closed || supported == false) return
        val needsProbe = supported == null && probe
        if (!needsProbe && gain == requested) return
        requested = gain
        if (scheduled.compareAndSet(false, true)) executor.execute(::drain)
    }

    // 只写最新请求；写入期间又有新请求时继续下一轮，不会并行写。
    private fun drain() {
        while (true) {
            val target = requested
            if (closed) return
            if (supported == null) {
                val applied = device.prepareHardwareGain(profile, target)
                if (applied == null) {
                    supported = false
                    UsbHardwareVolumeStatus.supported = false
                    appliedGain = 1f
                    scheduled.set(false)
                    return
                }
                supported = true
                UsbHardwareVolumeStatus.supported = true
                appliedGain = applied
                written = target
            } else if (supported != true) {
                scheduled.set(false)
                return
            } else if (target != written) {
                device.setHardwareGain(target)?.let { appliedGain = it }
                written = target
            }
            scheduled.set(false)
            if (requested == target || !scheduled.compareAndSet(false, true)) return
        }
    }

    /** 关闭设备前调用：停止写入，原始硬件音量由设备关闭时统一恢复。 */
    fun close() {
        closed = true
        executor.shutdown()
        runCatching { executor.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }
}

/**
 * 协调硬件音量开关切换与 USB 原生在途缓冲：
 * 关闭开关时先切回软件衰减，待切换前已进入 USB 管道的满幅 PCM 播完（含少量 DAC FIFO 余量）后再推满硬件，
 * 避免硬件提前拉满把缓冲里残留的满幅 PCM 直接按满音量播出。
 */
internal class UsbHardwareVolumeTransition(private val marginFrames: Long = 0L) {
    private var maximizeAfterFrame: Long? = null

    /** 清空输出缓冲后不再残留旧满幅 PCM，若正在等待推满则立刻放行。 */
    @Synchronized fun onFlush() {
        if (maximizeAfterFrame != null) maximizeAfterFrame = 0L
    }

    /** 计算本批 PCM 的软件增益，并返回此时应下发给硬件的目标增益（null 表示暂维持当前硬件衰减）。 */
    @Synchronized fun step(
        hardwareEnabled: Boolean,
        systemGain: Float,
        hardwareAppliedGain: Float,
        completedFrames: Long,
        pendingFrames: Long,
    ): Decision {
        if (hardwareEnabled) {
            maximizeAfterFrame = null
            return Decision(
                hardwareTarget = systemGain,
                softwareGain = usbSoftwareCompensation(systemGain, hardwareAppliedGain),
            )
        }
        if (hardwareAppliedGain >= 1f) {
            maximizeAfterFrame = null
            return Decision(hardwareTarget = 1f, softwareGain = systemGain)
        }
        val deadline = maximizeAfterFrame ?: (completedFrames + pendingFrames + marginFrames).also {
            maximizeAfterFrame = it
        }
        val drained = pendingFrames <= 0L || completedFrames >= deadline
        return Decision(
            hardwareTarget = if (drained) 1f else null,
            softwareGain = systemGain,
        )
    }

    internal data class Decision(val hardwareTarget: Float?, val softwareGain: Float)
}

/**
 * 软件需要补足的增益：目标与硬件已生效增益之比，差距在 0.5dB 内视为已到位（保持比特完美）。
 * 硬件比目标小（尚未调大）时不放大，只是短暂偏小。
 */
internal fun usbSoftwareCompensation(target: Float, hardware: Float): Float {
    if (target <= 0f || hardware <= 0f) return 0f
    val ratio = target / hardware
    return if (ratio >= .944f) 1f else ratio
}
