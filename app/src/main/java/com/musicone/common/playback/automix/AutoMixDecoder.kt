package com.musicone

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import com.musicone.nativeaudio.automix.AutoMixPcmStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/** 离线解码结果：44.1kHz 立体声（与实验代码 load_resampled 一致，存放在原生内存），附播放链路看到的原始格式。用完 close。 */
internal class AutoMixDecoded(
    /** 解码器输出格式（播放链路里输出端收到的同一格式）。 */
    val format: AutoMixPcmFormat,
    /** 首个解码样本的媒体时间。 */
    val firstMediaUs: Long,
    /** pcm 首帧在整首 44.1kHz 序列里的帧号。 */
    val offset: Long,
    /** 整首在 44.1kHz 下的帧数；只解了开头时为 -1。 */
    val total: Long,
    /** 原始采样率下解到的帧数。 */
    val nativeFrames: Long,
    val pcm: AutoMixPcmStore,
    /** 由直连解码得到；此时 format 是系统解码器的输出格式，用作片段格式前须换成实时播放链路的格式。 */
    val direct: Boolean = false,
) : AutoCloseable {
    /** 44.1kHz 下保留的帧数。 */
    val frames: Long get() = pcm.frames
    /** 换格式后与原对象共用同一存储，只需 close 其中一个。 */
    fun withFormat(live: AutoMixPcmFormat) = AutoMixDecoded(live, firstMediaUs, offset, total, nativeFrames, pcm, direct)
    override fun close() = pcm.close()
}

/**
 * 自然过渡的离线解码：用与播放相同的媒体源工厂、解码器选择和缓存键，在独立线程上跑一个不出声的播放器，
 * 输出端换成抓取器。A 从头解到尾只保留尾段（保证时间轴与实时播放逐样本一致），B 只解开头。
 */
@OptIn(UnstableApi::class)
internal class AutoMixDecoder(private val context: Context, private val mediaSourceFactory: MediaSource.Factory) {

    /** keepTail=true 时保留最后 seconds 秒，否则只解前 seconds 秒。在后台协程调用。 */
    suspend fun decode(item: MediaItem, keepTail: Boolean, seconds: Double, label: String): AutoMixDecoded? {
        // 先走直连解码（快）；格式不在白名单或出错时改走播放器离线解码
        try {
            AutoMixDirectDecoder.decode(item, keepTail, seconds, label)?.let { return it }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w(TAG, "$label 直连解码出错，改用播放器离线解码", error) }
        return decodeWithPlayer(item, keepTail, seconds, label)
    }

    private suspend fun decodeWithPlayer(item: MediaItem, keepTail: Boolean, seconds: Double, label: String): AutoMixDecoded? {
        val started = SystemClock.elapsedRealtime()
        // 后台优先级：解码与界面渲染抢 CPU 时让界面先走
        val thread = HandlerThread("AutoMixDecode", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        // 播放器内部的播放线程默认是音频优先级（比界面还高），离线解码时会一直全速运转抢界面 CPU，必须单独给一个后台优先级线程
        val playbackThread = HandlerThread("AutoMixDecodePlayback", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        val handler = Handler(thread.looper)
        val done = CompletableDeferred<Boolean>()
        val capture = CaptureSink(keepTail, seconds, unsupported = { handler.post { done.complete(false) } }) {
            handler.post { done.complete(true) }
        }
        var player: ExoPlayer? = null
        handler.post {
            runCatching {
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = capture
                }.setMediaCodecSelector(automixCodecSelector).setEnableDecoderFallback(true)
                player = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(mediaSourceFactory)
                    // 离线解码的播放位置可能不随抓取前进，默认 50 秒缓冲上限会让读取停住；时长不设限，只按内存封顶
                    .setLoadControl(DefaultLoadControl.Builder()
                        .setBufferDurationsMs(DECODE_BUFFER_MS, DECODE_BUFFER_MS, 1_000, 1_000)
                        .setTargetBufferBytes(DECODE_BUFFER_BYTES)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build())
                    .setLooper(thread.looper)
                    .setPlaybackLooper(playbackThread.looper)
                    .build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) done.complete(true) }
                            override fun onPlayerError(error: PlaybackException) { done.complete(false) }
                        })
                        setMediaItem(item)
                        prepare()
                        play()
                    }
            }.onFailure { done.complete(false) }
        }
        // 不设固定总时长：慢设备上整首解码可能要一两分钟；只有连续 DECODE_STALL_MS 没有新数据才算卡死
        var ok = false
        try {
            var result: Boolean? = null
            while (result == null) {
                result = withTimeoutOrNull(1_000L) { done.await() }
                if (result == null && SystemClock.elapsedRealtime() - capture.progressAt > DECODE_STALL_MS) {
                    Log.i(TAG, "$label 解码停滞 ${DECODE_STALL_MS / 1000}s 未出数据，放弃（已解 ${capture.decodedMs / 1000}s）")
                    // 诊断：记录后台播放器为何不再推进（状态、播放意图、抑制原因、位置与缓冲）
                    handler.post {
                        player?.let { p ->
                            Log.i(TAG, "$label 停滞诊断：状态=${p.playbackState} 播放意图=${p.playWhenReady} 正在播放=${p.isPlaying} " +
                                "抑制=${p.playbackSuppressionReason} 位置=${p.currentPosition}ms 缓冲到=${p.bufferedPosition}ms " +
                                "加载中=${p.isLoading} 抓取末端=${capture.lastEndMs}ms")
                        }
                    }
                    result = false
                }
            }
            ok = result
        } finally {
            // 失败时存储在播放器线程上释放（释放后迟到的写入直接忽略）
            val keep = ok
            // 外部传入的播放线程不会随播放器释放而退出，release 返回后再手动退出
            handler.post { player?.release(); if (!keep) capture.discard(); playbackThread.quitSafely(); thread.quitSafely() }
        }
        val costMs = SystemClock.elapsedRealtime() - started
        val mediaMs = capture.decodedMs
        Log.i(TAG, "$label 解码${if (ok) "完成" else "失败"}：媒体 ${mediaMs / 1000}s，用时 ${costMs}ms" +
            if (costMs > 0) "（${"%.1f".format(mediaMs.toDouble() / costMs)} 倍速）" else "")
        return if (ok) capture.result() else null
    }

    /** 抓取输出端：不建 AudioTrack，收到即消费，位置随写入推进，因此解码以最快速度进行。 */
    private class CaptureSink(private val keepTail: Boolean, private val seconds: Double,
        private val unsupported: () -> Unit, private val enough: () -> Unit) :
        @Suppress("DEPRECATION") ForwardingAudioSink(DefaultAudioSink.Builder().build()) {
        private var format: AutoMixPcmFormat? = null
        private var firstMediaUs = C.TIME_UNSET
        private var offsetUs = 0L
        private var lastEndUs = 0L
        private var nativeFrames = 0L
        private var ended = false
        private var stopped = false
        private var store: AutoMixPcmStore? = null
        /** 复用的转换缓冲，避免每个解码块都新建数组。 */
        private var scratch = FloatArray(0)
        private var failed = false
        /** 最近一次收到数据的时刻（含开始时刻），用于判断解码是否卡死。 */
        @Volatile var progressAt = SystemClock.elapsedRealtime()
        /** 已解码的媒体时长（毫秒）。 */
        val decodedMs: Long get() = format?.let { nativeFrames * 1000L / it.sampleRate } ?: 0L
        /** 抓取到的最后一个样本的呈现时间（毫秒，含输出流偏移），诊断用。 */
        val lastEndMs: Long get() = lastEndUs / 1000L

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            val pcm = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW)
                AutoMixPcmFormat(inputFormat.sampleRate, outputChannels?.size ?: inputFormat.channelCount, inputFormat.pcmEncoding) else null
            // 杜比等编码直通、多声道或中途换格式：抓取不到可用 PCM，立即结束（否则播放位置不前进，只能等超时）
            if (pcm == null || !pcm.supported || (format != null && format != pcm)) {
                failed = true
                unsupported()
                return
            }
            if (format == null) {
                format = pcm
                store = AutoMixPcmStore(pcm.sampleRate, keepTail, seconds)
            }
        }

        override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { offsetUs = outputStreamOffsetUs }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val f = format
            val r = store
            if (f == null || r == null || failed || stopped) { buffer.position(buffer.limit()); return true }
            if (firstMediaUs == C.TIME_UNSET) firstMediaUs = presentationTimeUs - offsetUs
            val frames = buffer.remaining() / f.bytesPerFrame
            if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
            readPcmFrames(buffer, f, frames, scratch)
            buffer.position(buffer.position() + frames * f.bytesPerFrame)
            r.push(scratch, frames)
            nativeFrames += frames
            progressAt = SystemClock.elapsedRealtime()
            lastEndUs = presentationTimeUs + f.framesToUs(frames.toLong())
            if (!keepTail && r.headComplete) { stopped = true; enough() }
            return true
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            if (format == null) AudioSink.CURRENT_POSITION_NOT_SET else lastEndUs

        override fun playToEndOfStream() { ended = true }
        override fun isEnded(): Boolean = ended
        override fun hasPendingData(): Boolean = false
        override fun play() = Unit
        override fun pause() = Unit
        override fun handleDiscontinuity() = Unit
        override fun flush() { ended = false }
        override fun reset() = Unit
        override fun release() = Unit

        /** 成功时把存储交给结果（之后由结果负责释放）。 */
        fun result(): AutoMixDecoded? {
            val f = format ?: return null
            val r = store ?: return null
            if (failed || nativeFrames == 0L) { discard(); return null }
            r.finish()
            val total = if (keepTail) r.totalOut(nativeFrames) else -1L
            store = null
            return AutoMixDecoded(f, firstMediaUs, r.offset, total, nativeFrames, r)
        }

        fun discard() { store?.close(); store = null }
    }

    companion object {
        private const val TAG = "MusicOneAutoMix"
        /** 连续这么久没有新解码数据就视为卡死（含建连、首包等待）。 */
        private const val DECODE_STALL_MS = 15_000L
        /** 后台解码播放器的缓冲：时长足够覆盖整首，内存与当前播放同样封顶 64 MiB。 */
        private const val DECODE_BUFFER_MS = 60 * 60_000
        private const val DECODE_BUFFER_BYTES = 64 * 1024 * 1024

        /** 与播放链路一致：FLAC 优先通用软件解码器，避免两边解码器不同导致样本不一致。 */
        private val automixCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            val selector = if (mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT
            selector.getDecoderInfos(mimeType, secure, tunneling).let { decoders ->
                if (mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) decoders.sortedBy { hiResAudioDecoderRank(it.name) } else decoders
            }
        }
    }
}
