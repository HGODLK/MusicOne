package com.musicone

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.musicone.nativeaudio.automix.AutoMixPcmStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 自然过渡的直连解码：MediaExtractor + MediaCodec 在一个循环里连续解，不经过播放器每 10ms 一次的调度，
 * 整首 A 从头解到尾通常只要几秒。只接 MP3／FLAC／WAV：这几种格式播放时同样走系统 MediaCodec（原生路由只接 APE、WV、DSD），
 * 帧序列和时间戳与实时播放一致；其余格式（AAC 编辑列表、Ogg 预跳等）仍走播放器离线解码，保证逐样本对齐。
 * 读取走与播放相同的数据源链（磁盘缓存、补充音源请求头、离线缓存键）。
 */
@OptIn(UnstableApi::class)
internal object AutoMixDirectDecoder {
    private const val TAG = "MusicOneAutoMix"
    private const val TIMEOUT_US = 10_000L
    private val DIRECT_MIMES = setOf(MediaFormat.MIMETYPE_AUDIO_MPEG, MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_RAW)
    /**
     * 解码速度上限（媒体时长 / 实际耗时）。真正的解码跑在系统编解码进程里，不受本线程优先级约束，
     * 不限速时会以上百倍速长时间占满一个大核并拉高频率，界面跟着掉帧；40 倍速下 4 分钟的 A 约 6 秒解完。
     */
    private const val MAX_SPEED = 40.0
    /** 实际进度落后限速线超过这么久就重新起算。 */
    private const val PACE_SLACK_MS = 500L

    /** 不适用（DASH、未登记数据源、非白名单格式）时返回 null，由调用方改走播放器离线解码。 */
    suspend fun decode(item: MediaItem, keepTail: Boolean, seconds: Double, label: String): AutoMixDecoded? = withContext(autoMixAnalysisDispatcher) {
        val config = item.localConfiguration ?: return@withContext null
        if (config.uri.toString().startsWith("data:")) return@withContext null
        val factory = AutoMixEngine.dataSourceFactory ?: return@withContext null
        val started = SystemClock.elapsedRealtime()
        val source = AutoMixMediaDataSource(factory, config.uri, config.customCacheKey)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var store: AutoMixPcmStore? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            if (mime !in DIRECT_MIMES) return@withContext null
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(input, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var format: AutoMixPcmFormat? = null
            var firstMediaUs = C.TIME_UNSET
            var nativeFrames = 0L
            var inputDone = false
            var scratch = FloatArray(0)
            // 限速的起算点；读取卡顿导致落后太多时重新起算，避免恢复后一口气全速追赶
            var paceAtMs = started
            var paceMediaMs = 0L
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT
                    val pcm = AutoMixPcmFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding)
                    if (!pcm.supported || (format != null && format != pcm)) {
                        Log.i(TAG, "$label 直连解码格式不支持：$pcm")
                        return@withContext null
                    }
                    if (format == null) { format = pcm; store = AutoMixPcmStore(pcm.sampleRate, keepTail, seconds) }
                    continue
                }
                if (out < 0) continue
                val f = format
                val r = store
                if (f != null && r != null && info.size > 0) {
                    val buffer = decoder.getOutputBuffer(out)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (firstMediaUs == C.TIME_UNSET) firstMediaUs = info.presentationTimeUs
                    val frames = info.size / f.bytesPerFrame
                    if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
                    readPcmFrames(buffer, f, frames, scratch)
                    r.push(scratch, frames)
                    nativeFrames += frames
                }
                decoder.releaseOutputBuffer(out, false)
                // 先还输出缓冲再等待，限速期间不占着解码器的缓冲
                if (f != null) {
                    val mediaMs = nativeFrames * 1000L / f.sampleRate
                    val now = SystemClock.elapsedRealtime()
                    val pause = autoMixDecodePauseMs(mediaMs - paceMediaMs, now - paceAtMs, MAX_SPEED)
                    if (pause > 0L) delay(pause)
                    else if (now - paceAtMs - (mediaMs - paceMediaMs) / MAX_SPEED > PACE_SLACK_MS) { paceAtMs = now; paceMediaMs = mediaMs }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (!keepTail && r != null && r.headComplete) break
            }
            val f = format ?: return@withContext null
            val r = store ?: return@withContext null
            if (nativeFrames == 0L) return@withContext null
            r.finish()
            val total = if (keepTail) r.totalOut(nativeFrames) else -1L
            val cost = SystemClock.elapsedRealtime() - started
            val mediaMs = nativeFrames * 1000L / f.sampleRate
            Log.i(TAG, "$label 直连解码完成（$mime）：媒体 ${mediaMs / 1000}s，用时 ${cost}ms" +
                if (cost > 0) "（${"%.1f".format(mediaMs.toDouble() / cost)} 倍速）" else "")
            store = null   // 交给结果，由结果负责释放
            AutoMixDecoded(f, if (firstMediaUs == C.TIME_UNSET) 0L else firstMediaUs, r.offset, total, nativeFrames, r, direct = true)
        } finally {
            store?.close()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }
}

/** 直连解码限速：自起算点以来解出的媒体时长超过“耗时 × 倍速上限”时返回需要等待的毫秒数，否则为 0。 */
internal fun autoMixDecodePauseMs(decodedMediaMs: Long, elapsedMs: Long, maxSpeed: Double): Long {
    val earliestMs = (decodedMediaMs / maxSpeed).toLong()
    return (earliestMs - elapsedMs).coerceAtLeast(0L)
}

/** 把播放用的 Media3 数据源包成 MediaExtractor 可随机读取的 MediaDataSource；顺序读复用同一连接，跳读时重开。 */
@OptIn(UnstableApi::class)
private class AutoMixMediaDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val key: String?,
) : MediaDataSource() {
    private var source: DataSource? = null
    private var position = -1L
    /** -2 未知（尚未打开），-1 服务器未声明长度。 */
    private var length = -2L

    @Synchronized override fun getSize(): Long {
        if (length == -2L) open(0L)
        return length
    }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length == -2L) open(0L)
        if (length >= 0 && position >= length) return -1
        if (source == null || position != this.position) open(position)
        val read = try { source!!.read(buffer, offset, size) } catch (error: IOException) { throw error }
        if (read == C.RESULT_END_OF_INPUT) return -1
        this.position += read
        return read
    }

    private fun open(at: Long) {
        closeSource()
        val next = factory.createDataSource()
        val opened = next.open(DataSpec.Builder().setUri(uri).setPosition(at).setKey(key)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
        if (length == -2L) length = if (opened == C.LENGTH_UNSET.toLong()) -1L else at + opened
        source = next
        position = at
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    @Synchronized override fun close() = closeSource()
}
