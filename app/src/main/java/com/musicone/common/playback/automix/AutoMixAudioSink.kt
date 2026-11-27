package com.musicone

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.musicone.nativeaudio.NativePcmResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** 交给音频输出端执行的过渡任务（混音片段已按当前歌 A 的采样率渲染好）。 */
internal class AutoMixSinkJob(
    val format: AutoMixPcmFormat,
    /** 预渲染好的立体声交错浮点 PCM，播完后调用 releaseClip 释放内存。 */
    clip: FloatArray,
    /** 过渡片段起点对应的 A 歌媒体时间（微秒）。 */
    val clipStartUs: Long,
    /** 片段开头还保持 A 原声的长度（微秒），实时播放的 A 必须在这个点之前切进来。 */
    val aDryUntilUs: Long,
    /** 片段时间到 B 原曲时间（秒）的映射表，每 10ms 一个点。 */
    val mapB: DoubleArray,
    /** B 原曲 0 秒对应的媒体时间。 */
    val bOriginUs: Long = 0L,
) {
    /** 片段帧数，释放片段数据后仍保留。 */
    val frames: Long = clip.size / 2L
    @Volatile var clip: FloatArray = clip
        private set

    /** 片段已全部送出：B 之后整首直通，十几 MB 的片段不再需要，及早让出内存给下一首的分析。 */
    fun releaseClip() { clip = FloatArray(0) }
    fun bSourceAt(clipFrame: Long): Double {
        val t = clipFrame.toDouble() / format.sampleRate / 0.01
        val i = t.toInt().coerceIn(0, mapB.size - 2)
        val f = (t - i).coerceIn(0.0, 1.0)
        return mapB[i] + (mapB[i + 1] - mapB[i]) * f
    }
}

/**
 * 自然过渡的输出接管：包在整条输出链最外层。
 * 实时 A 到达片段起点后，按 1:1 时长消耗 A（之后是 B）的解码数据，改为输出预渲染片段；
 * 片段末尾 B 已回到原速原音量，与实时 B 等功率交叉 20ms 后直通。
 * B 的格式与 A 不同时，片段播放期间不让内层重建 AudioTrack（那会断音），而是在这里把 B 转成 A 的格式。
 * 片段放完后：内层是固定采样率输出层（seamlessFormatChange 为真，换输入格式不重建）时，冲出转换残余后按 B 的真实格式交下去并退回直通；
 * 否则继续整首转换，直到下一次跳转或换歌。
 */
@OptIn(UnstableApi::class)
internal class AutoMixAudioSink(
    sink: AudioSink,
    private val seamlessFormatChange: () -> Boolean = { false },
) : ForwardingAudioSink(sink) {
    interface Listener {
        /** 片段第一帧已写出（播放线程）。 */
        fun onClipStarted()
        /** 片段最后一帧已写出，之后为实时 B 直通（播放线程）。 */
        fun onClipWritten()
        /** 被跳转、换歌或格式变化打断（播放线程）。 */
        fun onAborted(reason: String)
    }

    private enum class Phase { IDLE, ARMED, CLIP_A, CLIP_B, PASS_B }

    @Volatile var listener: Listener? = null
    @Volatile private var pendingJob: AutoMixSinkJob? = null
    @Volatile private var phase = Phase.IDLE
    private var job: AutoMixSinkJob? = null

    private var inputFormat: Format? = null
    private var inputChannels: IntArray? = null
    private var pcm: AutoMixPcmFormat? = null
    /** 实时播放链路当前收到的 PCM 格式（直连解码的 A 要按它输出片段）。 */
    val livePcmFormat: AutoMixPcmFormat? get() = pcm
    private var streamOffsetUs = 0L

    private var clipFrame = 0L
    private var xfadeAFrames = 0L
    private var ptsAtClip0 = 0L

    private var bInputFormat: Format? = null
    private var bInputChannels: IntArray? = null
    private var bPcm: AutoMixPcmFormat? = null
    private var converter: NativePcmResampler? = null
    private var converterSkip = 0
    private var bFirstMediaUs = C.TIME_UNSET
    private var budget = 0L
    private var readFrame = -1L
    private val queue = AutoMixFrameQueue()

    private var pending: ByteBuffer? = null
    private var pendingPts = 0L
    /** 片段与转换残余都已产出，等 pending 交完就按 B 的真实格式退回直通。 */
    private var exitPassBPending = false
    private var scratch = FloatArray(0)

    @Volatile private var clockClipUs = 0L
    @Volatile private var clockAtMs = 0L
    @Volatile private var playing = false
    /** 已经切回直通转发，但过渡片段的尾巴还在底层缓冲里没播完，此时过渡时钟继续跟着实际输出位置走。 */
    @Volatile private var tailClock = false

    /** 主线程调用：挂上待执行的过渡任务，等 A 播到切入点时接管输出。 */
    fun arm(job: AutoMixSinkJob) { pendingJob = job }

    /** 主线程调用：取消还没开始的过渡；如果已经开始播混音片段了就让它播完，不能中途硬退回 A。 */
    fun disarm() { pendingJob = null }

    val active: Boolean get() = phase == Phase.CLIP_A || phase == Phase.CLIP_B
    /**
     * 过渡后 B 与 A 格式不同、仍在把 B 转成 A 的格式输出（整首如此，直到跳转或换歌）。
     * 这期间实时格式不是 B 的真实格式，片段也无法接管，控制器不能分析或排程下一首。
     */
    val convertingB: Boolean get() = phase == Phase.PASS_B
    /** 是否还需要继续推进过渡时钟（播混音片段或尾巴还在底层缓冲里时为 true）。 */
    private val clocking: Boolean get() = active || phase == Phase.PASS_B || tailClock

    /** 计算当前人耳实际听到的片段进度（毫秒，播放时按系统时间顺滑推进，暂停即停）。 */
    fun clipClockMs(): Long {
        val base = clockClipUs / 1000L
        if (!playing || !clocking) return base
        return base + (SystemClock.elapsedRealtime() - clockAtMs).coerceIn(0L, 250L)
    }

    /** 当前片段（播放线程写入，主线程只读）。 */
    val currentJob: AutoMixSinkJob? get() = job

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        when (phase) {
            Phase.CLIP_A, Phase.CLIP_B -> {
                // B 的格式：吞掉，不让内层重建（重建会清空 AudioTrack 造成断音），B 的数据在这里转换
                bInputFormat = inputFormat
                bInputChannels = outputChannels
                bPcm = pcmFormatOf(inputFormat, outputChannels)
                if (bPcm?.supported != true) abort("B 格式不受支持")
                else if (phase == Phase.CLIP_A) beginB()
                else resetConverter()
                return
            }
            Phase.PASS_B -> finishOverride()
            else -> Unit
        }
        this.inputFormat = inputFormat
        this.inputChannels = outputChannels
        pcm = pcmFormatOf(inputFormat, outputChannels)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        val changed = outputStreamOffsetUs != streamOffsetUs
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        // 片段播放中输出流切换 = A 已送完，下一媒体项 B 开始
        if (changed && phase == Phase.CLIP_A) beginB()
    }

    /**
     * 原样交给内层但只写进一部分的那块数据。内层规定半块数据重试时必须原样再交同一块，
     * 所以这块写完之前不能切换阶段（例如恰好在此时开始接管片段），否则内层参数校验失败、播放器报错。
     */
    private var passBuffer: ByteBuffer? = null

    private fun pass(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        passBuffer = if (consumed) null else buffer
        return consumed
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!flushPending()) return false
        if (exitPassBPending) exitPassB()
        passBuffer?.let { held ->
            if (held === buffer) return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
            passBuffer = null
        }
        if (phase == Phase.IDLE) {
            val next = pendingJob
            if (next != null) startArmed(next)
        } else if (phase == Phase.PASS_B && pendingJob != null) {
            // 上一次过渡的格式转换还没结束，没法接新片段，必须通知控制器把刚加进队列的 B 撤掉。
            pendingJob = null
            listener?.onAborted("上一次过渡的格式转换尚未结束")
        }
        when (phase) {
            Phase.IDLE -> return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
            Phase.ARMED -> {
                val current = job ?: return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
                if (pendingJob !== current) {
                    phase = Phase.IDLE
                    job = null
                    return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
                }
                val mediaUs = presentationTimeUs - streamOffsetUs
                if (mediaUs < current.clipStartUs) return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
                if (mediaUs > current.clipStartUs + current.aDryUntilUs - DRY_MARGIN_US) {
                    abort(MISSED_REASON)
                    return pass(buffer, presentationTimeUs, encodedAccessUnitCount)
                }
                pendingJob = null
                clipFrame = ((mediaUs - current.clipStartUs) * current.format.sampleRate / C.MICROS_PER_SECOND).coerceAtLeast(0L)
                ptsAtClip0 = presentationTimeUs - current.format.framesToUs(clipFrame)
                xfadeAFrames = 0L
                // 过渡时钟从本片段重新起算：上一段过渡直通 B 期间时钟一直在累加，不重置会被当成片段已放完
                resetClock(super.getCurrentPositionUs(false).takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET }
                    ?.let { (it - ptsAtClip0).coerceAtLeast(0L) } ?: 0L)
                phase = Phase.CLIP_A
                listener?.onClipStarted()
                return consumeA(buffer, presentationTimeUs)
            }
            Phase.CLIP_A -> return consumeA(buffer, presentationTimeUs)
            Phase.CLIP_B, Phase.PASS_B -> return consumeB(buffer, presentationTimeUs)
        }
    }

    private fun startArmed(next: AutoMixSinkJob) {
        tailClock = false
        val format = pcm
        if (format == null || format != next.format) {
            pendingJob = null
            listener?.onAborted("A 的输出格式与分析时不一致")
            return
        }
        job = next
        phase = Phase.ARMED
    }

    /** 片段时间与 A 输入 1:1；开头 20ms 从实时 A 等功率交叉到片段（此处片段内仍是原样 A）。 */
    private fun consumeA(buffer: ByteBuffer, pts: Long): Boolean {
        val current = job ?: return pass(buffer, pts, 1)
        val format = current.format
        val frames = buffer.remaining() / format.bytesPerFrame
        ensureScratch(frames)
        val xf = (format.sampleRate * XFADE_S).toLong()
        if (xfadeAFrames < xf) readPcmFrames(buffer, format, frames, scratch)
        for (i in 0 until frames) {
            val c = clipFrame + i
            val (l, r) = clipAt(current, c)
            if (xfadeAFrames < xf) {
                val x = xfadeAFrames.toDouble() / xf
                val g0 = cos(PI / 2 * x).toFloat()
                val g1 = sin(PI / 2 * x).toFloat()
                scratch[2 * i] = scratch[2 * i] * g0 + l * g1
                scratch[2 * i + 1] = scratch[2 * i + 1] * g0 + r * g1
                xfadeAFrames++
            } else {
                scratch[2 * i] = l
                scratch[2 * i + 1] = r
            }
        }
        clipFrame += frames
        buffer.position(buffer.position() + frames * format.bytesPerFrame)
        emit(scratch, frames, format, pts)
        return true
    }

    private fun beginB() {
        val current = job ?: return
        if (phase == Phase.CLIP_A) phase = Phase.CLIP_B
        if (bPcm == null) {
            // 尚未收到 B 的 configure：B 与 A 同格式，渲染器不会重新配置
            bInputFormat = inputFormat
            bInputChannels = inputChannels
            bPcm = current.format
        }
        bFirstMediaUs = C.TIME_UNSET
        budget = 0L
        readFrame = -1L
        queue.clear()
        resetConverter()
    }

    private fun resetConverter() {
        converter?.close()
        converter = null
        converterSkip = 0
        val current = job ?: return
        val b = bPcm ?: return
        if (b.sampleRate != current.format.sampleRate) {
            converter = NativePcmResampler(b.sampleRate, current.format.sampleRate, b.channels, b.encoding)
            converterSkip = resamplerDelayFrames(b.sampleRate, current.format.sampleRate)
        }
    }

    /** B 的输入：转成 A 的格式入队；片段播完前按产出帧数 1:1 输出片段，之后交叉到实时 B。 */
    private fun consumeB(buffer: ByteBuffer, pts: Long): Boolean {
        val current = job ?: return pass(buffer, pts, 1)
        val b = bPcm ?: return pass(buffer, pts, 1)
        val out = current.format
        val inFrames = buffer.remaining() / b.bytesPerFrame
        if (bFirstMediaUs == C.TIME_UNSET) {
            bFirstMediaUs = pts - streamOffsetUs
            queue.clear(0L)
        }
        val produced = convertB(buffer, b, inFrames)
        buffer.position(buffer.position() + inFrames * b.bytesPerFrame)
        val switchStart = current.frames - (out.sampleRate * XFADE_S).toLong()
        ensureScratch(produced + 4096)
        var n = 0
        var exitAfterClip = false
        if (phase == Phase.CLIP_B && readFrame < 0) {
            budget += produced
            while (budget > 0 && clipFrame < switchStart) {
                val (l, r) = clipAt(current, clipFrame)
                if (n * 2 + 1 >= scratch.size) ensureScratch(n + 4096)
                scratch[2 * n] = l; scratch[2 * n + 1] = r
                n++; clipFrame++; budget--
            }
            if (clipFrame >= switchStart) {
                // 片段帧 c 对应 B 原曲 mapB(c)；转换后第 k 帧对应 B 媒体 bFirst + k/fs
                val bSource = current.bSourceAt(clipFrame)
                readFrame = ((bSource + (current.bOriginUs - bFirstMediaUs) / 1e6) * out.sampleRate).roundToLong().coerceAtLeast(queue.start)
                budget = 0L
            }
        }
        if (readFrame >= 0) {
            while (queue.has(readFrame)) {
                if (n * 2 + 1 >= scratch.size) ensureScratch(n + 4096)
                var l = queue.left(readFrame)
                var r = queue.right(readFrame)
                if (phase == Phase.CLIP_B) {
                    val xf = current.frames - switchStart
                    val x = ((clipFrame - switchStart).toDouble() / xf.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
                    val (cl, cr) = clipAt(current, clipFrame)
                    val g0 = cos(PI / 2 * x).toFloat()
                    val g1 = sin(PI / 2 * x).toFloat()
                    l = cl * g0 + l * g1
                    r = cr * g0 + r * g1
                    clipFrame++
                    if (clipFrame >= current.frames) {
                        phase = Phase.PASS_B
                        current.releaseClip()
                        listener?.onClipWritten()
                        val differs = converter != null || b != out
                        exitAfterClip = differs && seamlessFormatChange()
                        if (differs && !exitAfterClip) {
                            Log.i(TAG, "片段放完：A 是 ${out.label}，B 是 ${b.label}，当前输出不能中途换格式（比特完美／USB 独占／LG 原生等）；" +
                                "B 本首由自然过渡层转成 A 的格式播放，这首播完前不安排下一次过渡")
                        }
                    }
                }
                scratch[2 * n] = l; scratch[2 * n + 1] = r
                n++; readFrame++
            }
            if (exitAfterClip) {
                // 转换器里还压着几毫秒 B：冲出来接在后面，读完正好接上下一块原样的 B
                drainConverterIntoQueue()
                while (queue.has(readFrame)) {
                    if (n * 2 + 1 >= scratch.size) ensureScratch(n + 4096)
                    scratch[2 * n] = queue.left(readFrame); scratch[2 * n + 1] = queue.right(readFrame)
                    n++; readFrame++
                }
                exitPassBPending = true
            }
            queue.dropBefore(readFrame)
        } else {
            // 尚未交叉：只保留最近 2 秒，供交叉时回读
            queue.dropBefore(queue.end - out.sampleRate * 2L)
        }
        if (n > 0) emit(scratch, n, out, pts)
        // 两首格式完全一致时，队列读平后输出就是 B 的原始样本与时间戳，可以无缝退回纯转发，下一次过渡才能接管
        if (phase == Phase.PASS_B && converter == null && b == out && readFrame == queue.end) {
            leavePassB()
            Log.i(TAG, "片段放完：A、B 同为 ${b.label}，自然过渡层不再处理，B 原样往下交")
        }
        // 转换数据已全部交给内层才能换格式；没交完的等下一次 handleBuffer 开头
        if (exitPassBPending && pending == null) exitPassB()
        return true
    }

    /** 冲出转换器残余入队：与 convertB 一样按 S32 读成浮点并扣掉固定延迟。 */
    private fun drainConverterIntoQueue() {
        val conv = converter ?: return
        val b = bPcm ?: return
        val s32 = ByteBuffer.wrap(conv.drain()).order(ByteOrder.LITTLE_ENDIAN)
        val frames = s32.remaining() / (4 * b.channels)
        if (frames <= 0) return
        val tmp = FloatArray(frames * 2)
        readPcmFrames(s32, AutoMixPcmFormat(0, b.channels, C.ENCODING_PCM_32BIT), frames, tmp)
        val skip = minOf(converterSkip, frames)
        converterSkip -= skip
        val kept = frames - skip
        if (kept <= 0) return
        if (skip > 0) System.arraycopy(tmp, skip * 2, tmp, 0, kept * 2)
        queue.append(tmp, kept)
    }

    /** 按 B 的真实格式交给内层（固定采样率输出层不重建 AudioTrack）并退回直通。 */
    private fun exitPassB() {
        exitPassBPending = false
        converter?.close()
        converter = null
        val aLabel = pcm?.label
        val bLabel = bPcm?.label
        val format = bInputFormat
        if (format != null) {
            runCatching { super.configure(format, 0, bInputChannels) }
                .onFailure { Log.w(TAG, "片段放完后按 B 的格式配置内层失败", it) }
        }
        leavePassB()
        Log.i(TAG, "片段放完：A 是 $aLabel，B 是 $bLabel，已把 B 的真实格式交给固定采样率输出层（不重建 AudioTrack），" +
            "自然过渡层不再处理，下一首可以照常过渡")
    }

    /** 退回纯转发：内层本就按同一格式配置，不重建；过渡时钟由 tailClock 继续推进到可闻的片段末尾。 */
    private fun leavePassB() {
        tailClock = true
        phase = Phase.IDLE
        job = null
        bInputFormat?.let { inputFormat = it; inputChannels = bInputChannels }
        bPcm?.let { pcm = it }
        queue.clear()
        bInputFormat = null
        bInputChannels = null
        bPcm = null
        readFrame = -1L
    }

    private fun convertB(buffer: ByteBuffer, b: AutoMixPcmFormat, frames: Int): Int {
        val conv = converter
        if (conv == null) {
            ensureScratch(frames)
            readPcmFrames(buffer, b, frames, scratch)
            queue.append(scratch, frames)
            return frames
        }
        val bytes = ByteArray(frames * b.bytesPerFrame)
        buffer.duplicate().get(bytes)
        val s32 = ByteBuffer.wrap(conv.convert(bytes)).order(ByteOrder.LITTLE_ENDIAN)
        val outFrames = s32.remaining() / (4 * b.channels)
        ensureScratch(outFrames)
        readPcmFrames(s32, AutoMixPcmFormat(0, b.channels, C.ENCODING_PCM_32BIT), outFrames, scratch)
        var skip = minOf(converterSkip, outFrames)
        converterSkip -= skip
        val kept = outFrames - skip
        if (kept > 0) {
            if (skip > 0) System.arraycopy(scratch, skip * 2, scratch, 0, kept * 2)
            queue.append(scratch, kept)
        }
        return kept
    }

    private fun clipAt(job: AutoMixSinkJob, frame: Long): Pair<Float, Float> {
        if (frame < 0 || frame >= job.frames || frame * 2 + 1 >= job.clip.size) return 0f to 0f
        val i = (frame * 2).toInt()
        return job.clip[i] to job.clip[i + 1]
    }

    private fun emit(src: FloatArray, frames: Int, format: AutoMixPcmFormat, pts: Long) {
        val out = ByteBuffer.allocateDirect(frames * format.bytesPerFrame).order(ByteOrder.nativeOrder())
        writePcmFrames(src, 0, frames, format, out)
        out.flip()
        pending = out
        pendingPts = pts
        flushPending()
    }

    private fun flushPending(): Boolean {
        val out = pending ?: return true
        super.handleBuffer(out, pendingPts, 1)
        if (out.hasRemaining()) return false
        pending = null
        return true
    }

    private fun ensureScratch(frames: Int) {
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (clocking && position != AudioSink.CURRENT_POSITION_NOT_SET) {
            clockClipUs = (position - ptsAtClip0).coerceAtLeast(0L)
            clockAtMs = SystemClock.elapsedRealtime()
        }
        return position
    }

    override fun hasPendingData(): Boolean = pending != null || super.hasPendingData()

    override fun playToEndOfStream() {
        if (!flushPending()) return
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = pending == null && super.isEnded()

    override fun play() { playing = true; super.play() }
    override fun pause() { playing = false; super.pause() }

    override fun flush() {
        pending = null
        passBuffer = null
        exitPassBPending = false
        val override = phase == Phase.CLIP_B || phase == Phase.PASS_B
        val wasActive = active
        // 片段开始前在 A 内跳转：保持待命，再次走到切入点时照常接管（错过则由 ARMED 分支取消）
        val keep = if (!wasActive && phase != Phase.PASS_B) pendingJob else null
        super.flush()
        if (override) restoreBFormat()
        endPhase(if (wasActive) "跳转或换歌" else null, keep)
    }

    override fun reset() {
        pending = null
        passBuffer = null
        exitPassBPending = false
        val wasActive = active
        endPhase(if (wasActive) "播放器重置" else null)
        bPcm = null
        super.reset()
    }

    /** 跳转后内层仍是 A 的格式而数据已是 B：按 B 的真实格式重新配置（此时已清空，不影响听感）。 */
    private fun restoreBFormat() {
        val format = bInputFormat ?: return
        inputFormat = format
        inputChannels = bInputChannels
        pcm = pcmFormatOf(format, bInputChannels)
        runCatching { super.configure(format, 0, bInputChannels) }
            .onFailure { Log.w(TAG, "跳转后按 B 格式重建输出失败", it) }
    }

    private fun finishOverride() {
        // B 之后的下一首重新配置：内层照常重建，与关闭过渡时一致
        endPhase(null)
    }

    private fun resetClock(clipUs: Long) {
        clockClipUs = clipUs
        clockAtMs = SystemClock.elapsedRealtime()
    }

    /** keep：跳转后仍要保留的待命任务；其余被丢掉的待命任务一律回调控制器，不能悄悄清掉。 */
    private fun endPhase(abortReason: String?, keep: AutoMixSinkJob? = null) {
        val wasArmedOrActive = phase != Phase.IDLE && phase != Phase.PASS_B
        val dropped = pendingJob?.takeIf { it !== keep }
        phase = Phase.IDLE
        tailClock = false
        exitPassBPending = false
        resetClock(0L)
        job = null
        pendingJob = keep
        converter?.close()
        converter = null
        queue.clear()
        bInputFormat = null
        bInputChannels = null
        bPcm = null
        readFrame = -1L
        if (abortReason != null && wasArmedOrActive) listener?.onAborted(abortReason)
        else if (dropped != null) listener?.onAborted("输出端重新配置，待命的过渡失效")
    }

    private fun abort(reason: String) {
        Log.w(TAG, "自然过渡接管取消：$reason")
        val wasActive = active
        // 由下面按真实原因回调一次；不能让 endPhase 再按“失效”回调，否则“已错过切入点”时控制器保不住分析结果
        pendingJob = null
        endPhase(null)
        listener?.onAborted(reason)
        if (wasActive) Log.w(TAG, "片段中途取消，内层格式保持为 A")
    }

    companion object {
        private const val TAG = "MusicOneAutoMix"
        private const val XFADE_S = 0.02
        private const val DRY_MARGIN_US = 300_000L
        /** 片段开始前播放位置已越过最晚接入点；控制器据此保留分析结果。 */
        const val MISSED_REASON = "已错过切入点"

        private val delayCache = HashMap<Pair<Int, Int>, Int>()

        /** 实测 swr 转换器的固定延迟（输出帧），交接时据此对齐实时 B。 */
        @Synchronized
        fun resamplerDelayFrames(inRate: Int, outRate: Int): Int = delayCache.getOrPut(inRate to outRate) {
            runCatching {
                NativePcmResampler(inRate, outRate, 1, C.ENCODING_PCM_FLOAT).use { probe ->
                    val n = inRate / 2
                    val at = n / 4
                    val input = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) input.putFloat(if (i == at) 1f else 0f)
                    val out = ByteBuffer.wrap(probe.convert(input.array()) + probe.drain()).order(ByteOrder.LITTLE_ENDIAN)
                    var best = 0
                    var peak = 0L
                    for (i in 0 until out.remaining() / 4) {
                        val v = kotlin.math.abs(out.getInt(i * 4).toLong())
                        if (v > peak) { peak = v; best = i }
                    }
                    (best - (at.toLong() * outRate / inRate).toInt()).coerceAtLeast(0)
                }
            }.getOrDefault(0)
        }

        fun pcmFormatOf(format: Format, outputChannels: IntArray?): AutoMixPcmFormat? {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
            val channels = outputChannels?.size ?: format.channelCount
            return AutoMixPcmFormat(format.sampleRate, channels, format.pcmEncoding)
        }
    }
}
