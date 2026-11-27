package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定采样率输出层：何时归一化、内层收到的固定格式、编码器延迟／填充修剪、
 * 上一首残留先于下一首写出、内层半块接收的续交、结束与跳转。
 */
@OptIn(UnstableApi::class)
class FixedRateAudioSinkTest {
    private val target = 48_000
    private val inner = RecordingAudioSink(AutoMixPcmFormat(target, 2, C.ENCODING_PCM_FLOAT))
    private var bypass = false
    private var rate: Int? = target

    /** 假转换器：每帧原样输出一次，冲出时给 2 帧 0.9。 */
    private class FakeResampler : FixedRateResampler {
        override fun convert(src: FloatArray, frames: Int) = src.copyOf(frames * 2)
        override fun drain() = FloatArray(4) { 0.9f }
        override fun close() = Unit
    }

    private val sink = FixedRateAudioSink(inner.sink, { rate }, { bypass }) { _, _ -> FakeResampler() }

    private val f44 = AutoMixPcmFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
    private val f44i16 = AutoMixPcmFormat(44_100, 2, C.ENCODING_PCM_16BIT)
    private val f48 = AutoMixPcmFormat(target, 2, C.ENCODING_PCM_FLOAT)

    private fun format(f: AutoMixPcmFormat, channels: Int = f.channels, delay: Int = 0, padding: Int = 0): Format =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(f.sampleRate).setChannelCount(channels)
            .setPcmEncoding(f.encoding).setEncoderDelay(delay).setEncoderPadding(padding).build()

    private fun feed(f: AutoMixPcmFormat, frames: Int, ptsUs: Long = 0L, value: (Int) -> Float) =
        autoMixPcmBuffer(f, frames, value).also { assertTrue(sink.handleBuffer(it, ptsUs, 1)) }

    @Test fun otherFormatsReachTheInnerSinkAsFixedFloatStereo() {
        sink.configure(format(f44i16, delay = 5, padding = 7), 0, null)
        assertTrue(sink.normalizing)
        val fixed = inner.configured.single()
        assertEquals(target, fixed.sampleRate)
        assertEquals(2, fixed.channelCount)
        assertEquals(C.ENCODING_PCM_FLOAT, fixed.pcmEncoding)
        assertEquals("修剪已在本层做过", 0, fixed.encoderDelay)
        assertEquals(0, fixed.encoderPadding)
    }

    @Test fun alreadyFixedFormatPassesTheSameBufferThrough() {
        sink.configure(format(f48), 0, null)
        assertTrue(sink.normalizing)
        val buffer = feed(f48, 4) { 0.3f }
        assertSame(buffer, inner.buffers.single())
    }

    @Test fun bypassMissingRateAndMultichannelKeepTheOriginalFormat() {
        bypass = true
        val original = format(f44i16)
        sink.configure(original, 0, null)
        assertFalse(sink.normalizing)
        assertSame(original, inner.configured.last())
        val buffer = autoMixPcmBuffer(f44i16, 4) { 0.1f }
        assertTrue(sink.handleBuffer(buffer, 0L, 1))
        assertSame(buffer, inner.buffers.last())

        bypass = false
        rate = null
        sink.configure(original, 0, null)
        assertFalse(sink.normalizing)

        rate = target
        val surround = format(f44i16, channels = 6)
        sink.configure(surround, 0, null)
        assertFalse(sink.normalizing)
        assertSame(surround, inner.configured.last())
    }

    @Test fun consecutiveTracksKeepOneFormatAndThePreviousTailComesFirst() {
        sink.configure(format(f44), 0, null)
        feed(f44, 4) { 0.2f }
        sink.configure(format(f44i16), 0, null)
        assertEquals(2, inner.configured.size)
        assertEquals("两首交给内层的格式完全相同，AudioTrack 才能复用", inner.configured[0], inner.configured[1])
        feed(f44i16, 2, 100_000L) { 0.6f }
        assertEquals(8, inner.frames)
        assertEquals(0.2f, inner.left(3), 1e-6f)
        assertEquals("上一首的转换器尾巴", 0.9f, inner.left(4), 1e-6f)
        assertEquals(0.9f, inner.left(5), 1e-6f)
        assertEquals(0.6f, inner.left(6), 1e-4f)
    }

    @Test fun encoderDelayIsTrimmedOnceAndPaddingIsDroppedAtTheHandoff() {
        // 已是固定格式：处理链只剩修剪在工作
        sink.configure(format(f48, delay = 3, padding = 2), 0, null)
        feed(f48, 10) { (it + 1) / 100f }
        // 去掉开头 3 帧，末尾 2 帧先扣住
        assertEquals(5, inner.frames)
        assertEquals(0.04f, inner.left(0), 1e-6f)
        assertEquals(0.08f, inner.left(4), 1e-6f)
        feed(f48, 2, 10_000L) { 0.5f }
        assertEquals("扣住的帧随后续数据放出", 7, inner.frames)
        assertEquals(0.09f, inner.left(5), 1e-6f)

        // 换下一首：扣住的 2 帧（0.5、0.5）是编码器填充，丢掉
        sink.configure(format(f48), 0, null)
        val next = feed(f48, 3, 20_000L) { 0.7f }
        assertEquals(10, inner.frames)
        assertEquals(0.7f, inner.left(7), 1e-6f)
        assertSame("下一首不需要修剪，原样直通", next, inner.buffers.last())

        // 跳转后不再重复修剪开头的延迟
        sink.configure(format(f48, delay = 3), 0, null)
        feed(f48, 4, 30_000L) { 0.2f }
        assertEquals(11, inner.frames)
        sink.flush()
        feed(f48, 4, 0L) { 0.3f }
        assertEquals(15, inner.frames)
    }

    @Test fun halfAcceptedOutputIsResumedBeforeNewData() {
        sink.configure(format(f44), 0, null)
        inner.acceptBytes = 4 * 8
        assertTrue("输入全部进了处理链即算吃下", sink.handleBuffer(autoMixPcmBuffer(f44, 10) { 0.4f }, 0L, 1))
        assertEquals(4, inner.frames)
        assertTrue(sink.hasPendingData())
        inner.acceptBytes = null
        feed(f44, 2, 1_000L) { 0.7f }
        assertEquals(12, inner.frames)
        assertEquals(0.4f, inner.left(9), 1e-6f)
        assertEquals(0.7f, inner.left(10), 1e-6f)
    }

    @Test fun endOfStreamWritesTheResamplerTail() {
        sink.configure(format(f44), 0, null)
        feed(f44, 4) { 0.2f }
        sink.playToEndOfStream()
        assertEquals(6, inner.frames)
        assertEquals(0.9f, inner.left(5), 1e-6f)
    }

    @Test fun flushDropsWhatTheInnerSinkHasNotTaken() {
        sink.configure(format(f44), 0, null)
        inner.acceptBytes = 2 * 8
        sink.handleBuffer(autoMixPcmBuffer(f44, 6) { 0.5f }, 0L, 1)
        sink.flush()
        assertFalse(sink.hasPendingData())
        inner.acceptBytes = null
        feed(f44, 2) { 0.1f }
        assertEquals(4, inner.frames)
        assertEquals(0.1f, inner.left(2), 1e-6f)
    }
}
