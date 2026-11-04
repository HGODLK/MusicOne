package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PlaybackOutputStatusTest {
    @Test fun waitingDoesNotInventOutputSpecifications() {
        assertEquals("等待播放", playbackOutputDescription(null, null, UsbAudioOutputSnapshot()))
    }
    @Test fun systemFloatContainerIsNotPresentedAsSourceDepthOrDacConfirmation() {
        val text = playbackOutputDescription(PlaybackOutputSnapshot("系统输出", "有线耳机", 48000, 32, 2, true),
            null, UsbAudioOutputSnapshot())
        assertEquals("有线耳机 · 系统输出 · 32bit Float / 48kHz", text)
        assertFalse(text.contains("44.1kHz"))
    }
    @Test fun idleUsbDoesNotReplaceActualSystemOutput() {
        val text = playbackOutputDescription(PlaybackOutputSnapshot("系统输出", "蓝牙耳机", 48000, 16, 2), null,
            UsbAudioOutputSnapshot(UsbAudioOutputRoute.READY, "USB DAC", 192000, 32))
        assertTrue(text.contains("蓝牙耳机"))
        assertFalse(text.contains("192kHz"))
    }
    @Test fun activeUsbShowsInterfaceFormatAndCompatibilityConversion() {
        val text = playbackOutputDescription(null, null,
            UsbAudioOutputSnapshot(UsbAudioOutputRoute.DIRECT_UAC2, "USB DAC", 192000, 32, converted = true, channelCount = 2))
        assertEquals("USB DAC · UAC2 独占（兼容转换） · 32bit / 192kHz", text)
    }
    @Test fun lgShowsSubmittedFormatWithoutClaimingDirectIsVerified() {
        val text = playbackOutputDescription(null, PlaybackOutputSnapshot("Hi-Res", "LG G7 ThinQ", 44100, 24, 2,
            note = "应用提交规格；Direct／DAC 最终规格待设备验证"), UsbAudioOutputSnapshot())
        assertEquals("LG G7 ThinQ · Hi-Res · 24bit / 44.1kHz", text)
        assertFalse(text.contains("Bit-Perfect"))
    }
}
