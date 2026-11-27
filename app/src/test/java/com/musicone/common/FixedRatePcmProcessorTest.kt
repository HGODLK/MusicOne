package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 固定采样率处理器：格式判定、各编码转浮点立体声、重采样器的创建／冲出／关闭。 */
@OptIn(UnstableApi::class)
class FixedRatePcmProcessorTest {
    private val target = 48_000

    /** 假转换器：每帧原样输出一次，冲出时给 2 帧 0.9。 */
    private class FakeResampler : FixedRateResampler {
        var closed = false
        override fun convert(src: FloatArray, frames: Int) = src.copyOf(frames * 2)
        override fun drain() = FloatArray(4) { 0.9f }
        override fun close() { closed = true }
    }

    private val created = mutableListOf<Pair<Pair<Int, Int>, FakeResampler>>()
    private fun processor() = FixedRatePcmProcessor(target) { from, to -> FakeResampler().also { created += (from to to) to it } }

    private fun FixedRatePcmProcessor.start(rate: Int, channels: Int, encoding: Int): AudioProcessor.AudioFormat =
        configure(AudioProcessor.AudioFormat(rate, channels, encoding)).also { flush() }

    private fun drainOutput(p: FixedRatePcmProcessor): FloatArray {
        val out = p.output.order(ByteOrder.nativeOrder())
        return FloatArray(out.remaining() / 4) { out.float }
    }

    @Test fun targetFormatIsLeftAlone() {
        val p = processor()
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.start(target, 2, C.ENCODING_PCM_FLOAT))
        assertFalse(p.isActive)
        assertTrue(created.isEmpty())
    }

    @Test fun sixteenBitMonoAtTargetRateBecomesFloatStereo() {
        val p = processor()
        val format = AutoMixPcmFormat(target, 1, C.ENCODING_PCM_16BIT)
        assertEquals(AudioProcessor.AudioFormat(target, 2, C.ENCODING_PCM_FLOAT), p.start(target, 1, C.ENCODING_PCM_16BIT))
        assertTrue("同采样率不建转换器", created.isEmpty())
        val input = autoMixPcmBuffer(format, 3) { listOf(0.5f, -0.25f, 0f)[it] }
        p.queueInput(input)
        assertFalse(input.hasRemaining())
        val out = drainOutput(p)
        assertEquals(6, out.size)
        assertEquals(0.5f, out[0], 1e-4f); assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(-0.25f, out[2], 1e-4f); assertEquals(-0.25f, out[3], 1e-4f)
    }

    @Test fun highResIntegerAndFloatKeepTheirValues() {
        for (encoding in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val p = processor()
            // 浮点立体声需换采样率才工作；整数直接在目标采样率下测
            val rate = if (encoding == C.ENCODING_PCM_FLOAT) 44_100 else target
            p.start(rate, 2, encoding)
            val format = AutoMixPcmFormat(rate, 2, encoding)
            p.queueInput(autoMixPcmBuffer(format, 4) { 0.125f * (it + 1) })
            val out = drainOutput(p)
            assertEquals("$encoding", 8, out.size)
            for (i in 0 until 4) assertEquals("$encoding 第 $i 帧", 0.125f * (i + 1), out[i * 2], 1e-5f)
        }
    }

    @Test fun otherRatesGoThroughTheResamplerAndItsTailIsDrained() {
        val p = processor()
        assertEquals(AudioProcessor.AudioFormat(target, 2, C.ENCODING_PCM_FLOAT), p.start(44_100, 2, C.ENCODING_PCM_16BIT))
        assertEquals(44_100 to target, created.single().first)
        p.queueInput(autoMixPcmBuffer(AutoMixPcmFormat(44_100, 2, C.ENCODING_PCM_16BIT), 2) { 0.5f })
        assertEquals(4, drainOutput(p).size)
        p.queueEndOfStream()
        val tail = drainOutput(p)
        assertEquals(4, tail.size)
        assertEquals(0.9f, tail[0], 1e-6f)
        assertTrue(p.isEnded)
    }

    @Test fun flushRebuildsAndResetClosesTheResampler() {
        val p = processor()
        p.start(44_100, 2, C.ENCODING_PCM_FLOAT)
        val first = created.single().second
        p.flush()
        assertTrue("跳转后重建转换器，旧的关闭", first.closed)
        assertEquals(2, created.size)
        p.reset()
        assertTrue(created.last().second.closed)
    }

    @Test fun unsupportedFormatsAreRejectedWithoutBuildingAResampler() {
        val formats = listOf(
            AudioProcessor.AudioFormat(44_100, 6, C.ENCODING_PCM_16BIT),
            AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_AC3),
            AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT),
        )
        for (format in formats) {
            val p = processor()
            try {
                p.configure(format)
                fail("$format 应被拒绝")
            } catch (_: AudioProcessor.UnhandledAudioFormatException) {
            }
            p.flush()
            assertFalse(p.isActive)
            p.queueInput(ByteBuffer.allocate(16))
        }
        assertTrue(created.isEmpty())
    }
}
