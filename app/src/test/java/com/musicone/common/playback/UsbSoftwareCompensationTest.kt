package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbSoftwareCompensationTest {
    @Test fun 硬件到位后软件不再衰减() {
        assertEquals(1f, usbSoftwareCompensation(.5f, .5f), 0f)
        // 步长量化带来的 0.5dB 内误差视为到位，保持比特完美。
        assertEquals(1f, usbSoftwareCompensation(.49f, .501f), 0f)
    }

    @Test fun 硬件尚未跟上时由软件补足且从不放大() {
        // 刚打开开关，硬件仍推满：软件照常衰减，不会突然变大声。
        assertEquals(.3f, usbSoftwareCompensation(.3f, 1f), 1e-6f)
        // 调小音量时硬件还没写完：软件立即补足差值。
        assertEquals(.5f, usbSoftwareCompensation(.25f, .5f), 1e-6f)
        // 调大音量时硬件还没写完：只是短暂偏小，不放大。
        assertEquals(1f, usbSoftwareCompensation(.8f, .5f), 0f)
        assertEquals(0f, usbSoftwareCompensation(0f, .5f), 0f)
        assertEquals(0f, usbSoftwareCompensation(.5f, 0f), 0f)
    }

    @Test fun 关闭硬件音量等待旧满幅缓冲排空后再推满() {
        val transition = UsbHardwareVolumeTransition(marginFrames = 400L)
        // 仍在硬件模式：软件保持满幅，硬件跟踪系统音量。
        val active = transition.step(true, .2f, .2f, completedFrames = 1000L, pendingFrames = 3840L)
        assertEquals(.2f, active.hardwareTarget!!, 0f)
        assertEquals(1f, active.softwareGain, 0f)

        // 刚关闭开关：新写入 PCM 立即切回软件衰减，硬件暂维持原衰减等待 3840 + 400 帧排空。
        val closing = transition.step(false, .2f, .2f, completedFrames = 1000L, pendingFrames = 3840L)
        assertNull(closing.hardwareTarget)
        assertEquals(.2f, closing.softwareGain, 0f)

        // 未到截止帧前继续按兵不动。
        val mid = transition.step(false, .2f, .2f, completedFrames = 5239L, pendingFrames = 3840L)
        assertNull(mid.hardwareTarget)
        assertEquals(.2f, mid.softwareGain, 0f)

        // 旧满幅缓冲及余量播完后才推满硬件。
        val drained = transition.step(false, .2f, .2f, completedFrames = 5240L, pendingFrames = 3840L)
        assertEquals(1f, drained.hardwareTarget!!, 0f)
        assertEquals(.2f, drained.softwareGain, 0f)
    }

    @Test fun 清空缓冲或无在途音频时关闭开关立刻推满硬件() {
        val transition = UsbHardwareVolumeTransition(marginFrames = 400L)
        val closing = transition.step(false, .25f, .25f, completedFrames = 500L, pendingFrames = 3000L)
        assertNull(closing.hardwareTarget)

        // flush 清掉在途满幅帧后不再等待旧截止帧。
        transition.onFlush()
        val afterFlush = transition.step(false, .25f, .25f, completedFrames = 0L, pendingFrames = 1000L)
        assertEquals(1f, afterFlush.hardwareTarget!!, 0f)
        assertEquals(.25f, afterFlush.softwareGain, 0f)

        // 暂停排空后 pending 为 0 时也立刻推满。
        val idle = UsbHardwareVolumeTransition(marginFrames = 400L)
            .step(false, .25f, .25f, completedFrames = 500L, pendingFrames = 0L)
        assertEquals(1f, idle.hardwareTarget!!, 0f)
        assertEquals(.25f, idle.softwareGain, 0f)
    }
}
