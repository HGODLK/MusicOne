package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 输出端接管：用 1 kHz 立体声浮点驱动 AutoMixAudioSink，帧号与毫秒一一对应。
 * 覆盖接管时机、A→片段→B 的逐帧对齐、退回直通、错过切入点、半块重试、跳转与格式转换期间的回调。
 */
@OptIn(UnstableApi::class)
class AutoMixAudioSinkTest {
    private val f = AUTOMIX_TEST_FORMAT
    private lateinit var inner: RecordingAudioSink
    private lateinit var sink: AutoMixAudioSink
    private lateinit var listener: RecordingSinkListener

    @Before fun setUp() {
        inner = RecordingAudioSink(f)
        sink = AutoMixAudioSink(inner.sink)
        listener = RecordingSinkListener()
        sink.listener = listener
        sink.configure(autoMixRawFormat(f), 0, null)
    }

    /** 片段 200 帧、值恒为 0.5；片段帧 c 对应 B 原曲 c 毫秒。 */
    private fun job(clipStartUs: Long = 100_000L, aDryUntilUs: Long = 1_000_000L, format: AutoMixPcmFormat = f) =
        AutoMixSinkJob(format, FloatArray(200 * 2) { 0.5f }, clipStartUs, aDryUntilUs, DoubleArray(22) { it * 0.01 })

    private fun feedA(startFrame: Int, frames: Int = 50) =
        autoMixPcmBuffer(f, frames) { 0.1f }.also { assertTrue(sink.handleBuffer(it, startFrame * 1000L, 1)) }

    private fun bValue(k: Int) = 0.6f + k * 0.0001f

    private fun feedB(offsetUs: Long, startFrame: Int, frames: Int = 50, format: AutoMixPcmFormat = f) =
        autoMixPcmBuffer(format, frames) { bValue(startFrame + it) }
            .also { assertTrue(sink.handleBuffer(it, offsetUs + startFrame * 1000L, 1)) }

    @Test fun fullTransitionKeepsEveryFrameAlignedAndReturnsToPassThrough() {
        sink.arm(job())
        val a0 = feedA(0)
        val a1 = feedA(50)
        feedA(100)
        assertSame("切入点之前原样直通", a0, inner.buffers[0])
        assertSame(a1, inner.buffers[1])
        assertEquals(listOf("started"), listener.events)
        assertTrue(sink.active)
        // 片段开头 20ms 从实时 A 等功率交叉到片段
        assertEquals(0.1f, inner.left(100), 1e-6f)
        assertEquals(0.5f, inner.left(149), 1e-6f)

        val offset = 1_000_000_000L
        sink.setOutputStreamOffsetUs(offset)
        for (k in 0 until 200 step 50) feedB(offset, k)
        assertEquals(listOf("started", "written"), listener.events)
        assertFalse("格式一致时片段放完立即退回直通", sink.convertingB)
        assertFalse(sink.active)
        // 输出 = A 切入前 100 帧 + 片段 200 帧，B 原曲 200 帧之后接着直通
        assertEquals(300, inner.frames)
        assertEquals(0.5f, inner.left(100 + 179), 1e-6f)
        // 片段最后 20ms 与实时 B 等功率交叉：片段帧 199 处于第 19/20 步，对应 B 原曲第 199 帧
        val x = Math.PI / 2 * 19.0 / 20.0
        val expected = (0.5 * Math.cos(x) + bValue(199) * Math.sin(x)).toFloat()
        assertEquals("交叉段逐帧对齐实时 B", expected, inner.left(299), 1e-5f)

        val next = feedB(offset, 200)
        assertSame("退回直通后原样转发 B", next, inner.buffers.last())
        assertEquals(bValue(200), inner.left(300), 1e-6f)
    }

    @Test fun secondTransitionIsAcceptedAfterReturningToPassThroughAndItsClockRestarts() {
        sink.arm(job())
        for (s in 0 until 150 step 50) feedA(s)
        val offset = 1_000_000_000L
        sink.setOutputStreamOffsetUs(offset)
        for (k in 0 until 250 step 50) feedB(offset, k)
        // 尾段时钟继续推进：上一段的时钟走到很大
        inner.positionUs = offset + 240_000L
        sink.getCurrentPositionUs(false)
        assertTrue(sink.clipClockMs() > 100L)

        sink.arm(job(clipStartUs = 300_000L))
        feedB(offset, 250)
        assertEquals("第二次待命不能被拒", listOf("started", "written"), listener.events)
        inner.positionUs = offset + 300_000L
        feedB(offset, 300)
        assertEquals(listOf("started", "written", "started"), listener.events)
        sink.getCurrentPositionUs(false)
        assertTrue("新片段的时钟必须从 0 起算，否则会瞬间交接", sink.clipClockMs() < 5L)
    }

    @Test fun missedCutPointAbortsWithTheDedicatedReasonAndPassesThrough() {
        sink.arm(job(aDryUntilUs = 400_000L))
        val late = feedA(250)
        assertEquals(listOf("aborted:${AutoMixAudioSink.MISSED_REASON}"), listener.events)
        assertSame(late, inner.buffers.last())
        assertFalse(sink.active)
    }

    @Test fun mismatchedAnalysisFormatIsRejectedBeforeTakingOver() {
        sink.arm(job(format = AutoMixPcmFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        feedA(0)
        assertEquals(listOf("aborted:A 的输出格式与分析时不一致"), listener.events)
    }

    @Test fun halfWrittenBufferIsRetriedUnchangedEvenIfArmedMeanwhile() {
        inner.acceptBytes = 20 * f.bytesPerFrame
        val held = autoMixPcmBuffer(f, 50) { 0.1f }
        assertFalse(sink.handleBuffer(held, 100_000L, 1))
        sink.arm(job())
        assertFalse(sink.handleBuffer(held, 100_000L, 1))
        inner.acceptBytes = null
        assertTrue(sink.handleBuffer(held, 100_000L, 1))
        assertEquals(3, inner.buffers.size)
        inner.buffers.forEach { assertSame("半块重试必须原样交同一块", held, it) }
        assertTrue(listener.events.isEmpty())
        // 下一块仍在原样段内，可以从片段中间接入
        feedA(150)
        assertEquals(listOf("started"), listener.events)
    }

    @Test fun seekingInsideABeforeTheClipKeepsTheJobArmed() {
        sink.arm(job())
        feedA(0)
        sink.flush()
        assertTrue(listener.events.isEmpty())
        feedA(100)
        assertEquals(listOf("started"), listener.events)
    }

    @Test fun seekingDuringTheClipReportsAnInterruption() {
        sink.arm(job())
        feedA(100)
        sink.flush()
        assertEquals(listOf("started", "aborted:跳转或换歌"), listener.events)
        assertFalse(sink.active)
    }

    @Test fun differentBFormatKeepsConvertingAndRejectsNewJobsUntilTheNextItem() {
        val b16 = AutoMixPcmFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        sink.arm(job())
        feedA(100)
        // B 的配置被吞掉，不让内层重建
        sink.configure(autoMixRawFormat(b16), 0, null)
        val offset = 1_000_000_000L
        sink.setOutputStreamOffsetUs(offset)
        for (k in 0 until 250 step 50) feedB(offset, k, format = b16)
        assertEquals(1, inner.configured.size)
        assertEquals(listOf("started", "written"), listener.events)
        assertTrue("B 与 A 格式不同：整首转换输出", sink.convertingB)

        sink.arm(job(clipStartUs = 400_000L))
        feedB(offset, 250, format = b16)
        assertEquals("aborted:上一次过渡的格式转换尚未结束", listener.events.last())

        // 下一首重新配置：结束转换，内层照常重建
        sink.configure(autoMixRawFormat(f), 0, null)
        assertFalse(sink.convertingB)
        assertEquals(2, inner.configured.size)
    }

    @Test fun withFixedRateOutputTheSinkHandsBItsRealFormatRightAfterTheClip() {
        // 内层是固定采样率输出层：片段放完即按 B 的真实格式交下去，不再整首转换，也不拒绝下一次过渡
        val seamless = AutoMixAudioSink(inner.sink, seamlessFormatChange = { true })
        seamless.listener = listener
        seamless.configure(autoMixRawFormat(f), 0, null)
        val b16 = AutoMixPcmFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        seamless.arm(job())
        assertTrue(seamless.handleBuffer(autoMixPcmBuffer(f, 50) { 0.1f }, 100_000L, 1))
        seamless.configure(autoMixRawFormat(b16), 0, null)
        assertEquals("片段播放期间 B 的配置仍被吞掉", 2, inner.configured.size)
        val offset = 1_000_000_000L
        seamless.setOutputStreamOffsetUs(offset)
        for (k in 0 until 200 step 50) {
            assertTrue(seamless.handleBuffer(autoMixPcmBuffer(b16, 50) { bValue(k + it) }, offset + k * 1000L, 1))
        }
        assertEquals(listOf("started", "written"), listener.events)
        assertEquals(3, inner.configured.size)
        assertEquals("内层按 B 的真实编码配置", C.ENCODING_PCM_16BIT, inner.configured.last().pcmEncoding)
        assertFalse(seamless.convertingB)
        assertFalse(seamless.active)
        assertEquals("A 从片段起点送入，输出即片段 200 帧", 200, inner.frames)

        val next = autoMixPcmBuffer(b16, 50) { bValue(200 + it) }
        assertTrue(seamless.handleBuffer(next, offset + 200_000L, 1))
        assertSame("之后的 B 原样直通", next, inner.buffers.last())

        seamless.arm(job(clipStartUs = 400_000L, format = b16))
        assertTrue(seamless.handleBuffer(autoMixPcmBuffer(b16, 50) { 0.2f }, offset + 250_000L, 1))
        assertEquals("下一次过渡不再被拒", listOf("started", "written"), listener.events)
    }
}
