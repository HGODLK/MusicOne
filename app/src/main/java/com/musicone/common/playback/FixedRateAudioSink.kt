package com.musicone

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ChannelMappingAudioProcessor
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 系统混音路径的固定输出格式：任何 PCM 都转成“系统主输出采样率、立体声、浮点”再交给内层。
 * 安卓混音器本来就会把所有音轨重采样到主输出采样率，这里提前做一次（系统就不必再转），
 * 换来每首歌交给内层的格式完全相同，内层复用同一个 AudioTrack，切歌与自然过渡交接都不重建、不断音。
 *
 * 编码器延迟／填充在这里修剪（内层收到的固定格式不带延迟信息）：每首开头去掉延迟，交接时丢掉上一首末尾的填充。
 * bypass 为真（比特完美、USB 独占、LG 原生）或拿不到系统采样率、多声道、非 PCM 时原样透传。
 */
@OptIn(UnstableApi::class)
internal class FixedRateAudioSink(
    sink: AudioSink,
    private val targetRate: () -> Int?,
    private val bypass: () -> Boolean,
    private val resamplerFactory: (Int, Int) -> FixedRateResampler = ::nativeFixedRateResampler,
) : ForwardingAudioSink(sink) {
    /** 当前配置是否在归一化输出（自然过渡据此判断能否无缝交出 B 的真实格式）。 */
    @Volatile var normalizing = false
        private set

    private var pipeline: AudioProcessingPipeline? = null
    /** 上一首的处理链：已送结束标记，残留输出写完才轮到当前这首。 */
    private var draining: AudioProcessingPipeline? = null
    /** 已从处理链取出、内层还没吃完的数据（内层要求半块重试时原样再交同一块）。 */
    private var pending: ByteBuffer? = null
    private var pendingPts = 0L
    private var lastPts = 0L
    private var endQueued = false

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val rate = targetRate()
        val channels = outputChannels?.size ?: inputFormat.channelCount
        val skipReason = when {
            bypass() -> "比特完美／USB 独占／LG 原生输出"
            rate == null || rate <= 0 -> "拿不到系统主输出采样率"
            inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW -> "非 PCM（${inputFormat.sampleMimeType}）"
            inputFormat.pcmEncoding !in SUPPORTED_ENCODINGS -> "位深不支持"
            channels !in 1..2 -> "多声道"
            else -> null
        }
        val normalize = skipReason == null
        val inLabel = AutoMixPcmFormat(inputFormat.sampleRate, channels, inputFormat.pcmEncoding).label
        if (!normalize) {
            Log.i(TAG, "输出格式：$inLabel 原样交给系统（$skipReason）")
            releasePipelines()
            pending = null
            normalizing = false
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        val next = AudioProcessingPipeline(ImmutableList.of(
            EncoderTrimProcessor(inputFormat.encoderDelay.coerceAtLeast(0), inputFormat.encoderPadding.coerceAtLeast(0)),
            ChannelMappingAudioProcessor().apply { setChannelMap(outputChannels) },
            FixedRatePcmProcessor(rate!!, resamplerFactory),
        ))
        next.configure(AudioProcessor.AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding))
        next.flush()
        val old = pipeline
        if (old != null && normalizing) {
            // 上一首还在处理链里的数据（重采样残留等）先写完；它末尾的编码器填充在结束标记时丢掉
            draining?.reset()
            old.queueEndOfStream()
            draining = old
        } else {
            old?.reset()
            pending = null
        }
        pipeline = next
        endQueued = false
        normalizing = true
        val work = when {
            inputFormat.sampleRate != rate -> "重采样"
            !next.isOperational -> "无需转换"
            else -> "仅转位深／声道"
        }
        Log.i(TAG, "输出格式：$inLabel → 系统主输出 ${AutoMixPcmFormat(rate!!, 2, C.ENCODING_PCM_FLOAT).label}（$work，每首交给系统的格式相同，AudioTrack 可复用）")
        super.configure(fixedFormat(rate), 0, null)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!normalizing) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!writePending() || !finishDraining()) return false
        val current = pipeline ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // 已是固定格式且无需修剪：处理链不工作，原样交给内层
        if (!current.isOperational) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        lastPts = presentationTimeUs
        pump(current, buffer, presentationTimeUs)
        // 输入已全部进入处理链即算吃下；没写完的输出留在 pending，下次调用先续交
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (normalizing) {
            if (!writePending() || !finishDraining()) return
            val current = pipeline
            if (current != null && current.isOperational) {
                if (!endQueued) { current.queueEndOfStream(); endQueued = true }
                if (!pump(current, null, lastPts) || !current.isEnded) return
            }
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (!normalizing) return super.isEnded()
        val current = pipeline
        val chainEnded = current == null || !current.isOperational || current.isEnded
        return pending == null && draining == null && chainEnded && super.isEnded()
    }

    override fun hasPendingData(): Boolean = pending != null || super.hasPendingData()

    override fun flush() {
        pending = null
        draining?.reset()
        draining = null
        pipeline?.flush()
        endQueued = false
        super.flush()
    }

    override fun reset() {
        releasePipelines()
        pending = null
        normalizing = false
        super.reset()
    }

    override fun release() {
        releasePipelines()
        pending = null
        super.release()
    }

    /** 把 pending 交给内层，返回是否已全部交完。 */
    private fun writePending(): Boolean {
        val out = pending ?: return true
        super.handleBuffer(out, pendingPts, 1)
        if (out.hasRemaining()) return false
        pending = null
        return true
    }

    /** 上一首的残留写完后才放行当前这首。 */
    private fun finishDraining(): Boolean {
        val old = draining ?: return true
        if (!pump(old, null, lastPts)) return false
        if (!old.isEnded && old.getOutput().hasRemaining()) return false
        old.reset()
        draining = null
        return true
    }

    /** 送入 input（可为空，仅取残留输出）并把产出交给内层；内层吃不下时返回 false，剩余留在 pending。 */
    private fun pump(chain: AudioProcessingPipeline, input: ByteBuffer?, pts: Long): Boolean {
        while (true) {
            if (!writePending()) return false
            if (input != null && input.hasRemaining()) chain.queueInput(input)
            val out = chain.getOutput()
            if (!out.hasRemaining()) return true
            // 处理器的输出缓冲会被复用，交给内层前复制一份
            pending = ByteBuffer.allocateDirect(out.remaining()).order(ByteOrder.nativeOrder()).put(out).also { it.flip() }
            pendingPts = pts
        }
    }

    private fun releasePipelines() {
        pipeline?.reset()
        pipeline = null
        draining?.reset()
        draining = null
        endQueued = false
    }

    private fun fixedFormat(rate: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
        .setSampleRate(rate)
        .setChannelCount(2)
        .build()

    private companion object {
        const val TAG = "MusicOneOutput"
        val SUPPORTED_ENCODINGS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}

/**
 * 编码器延迟／填充修剪：开头去掉 delay 帧（只在配置后的第一次起播生效，跳转不再修剪），
 * 始终扣住末尾 padding 帧，结束标记时丢掉（交接到下一首时不让填充混进来）。
 */
@OptIn(UnstableApi::class)
internal class EncoderTrimProcessor(private val delayFrames: Int, private val paddingFrames: Int) : BaseAudioProcessor() {
    private var fresh = true
    private var delayBytesLeft = 0
    private var held = ByteArray(0)
    private var heldSize = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == Format.NO_VALUE || inputAudioFormat.bytesPerFrame <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        fresh = true
        return if (delayFrames == 0 && paddingFrames == 0) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val skip = minOf(delayBytesLeft, inputBuffer.remaining())
        inputBuffer.position(inputBuffer.position() + skip)
        delayBytesLeft -= skip
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val paddingBytes = held.size
        val total = heldSize + remaining
        val outLen = (total - paddingBytes).coerceAtLeast(0)
        val fromHeld = minOf(heldSize, outLen)
        val fromInput = outLen - fromHeld
        if (outLen > 0) {
            val out = replaceOutputBuffer(outLen)
            out.put(held, 0, fromHeld)
            val slice = inputBuffer.duplicate()
            slice.limit(slice.position() + fromInput)
            out.put(slice)
            out.flip()
        }
        inputBuffer.position(inputBuffer.position() + fromInput)
        // 剩下的（最多 padding 帧）留作下一次的开头
        System.arraycopy(held, fromHeld, held, 0, heldSize - fromHeld)
        heldSize -= fromHeld
        val rest = inputBuffer.remaining()
        inputBuffer.get(held, heldSize, rest)
        heldSize += rest
    }

    override fun onQueueEndOfStream() {
        heldSize = 0
    }

    override fun onFlush() {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        delayBytesLeft = if (fresh) delayFrames * bytesPerFrame else 0
        fresh = false
        held = ByteArray(paddingFrames * bytesPerFrame)
        heldSize = 0
    }

    override fun onReset() {
        held = ByteArray(0)
        heldSize = 0
        delayBytesLeft = 0
    }
}
