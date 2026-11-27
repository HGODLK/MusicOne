package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/*
 * 自然过渡单测共用的假对象。Media3 的 AudioSink / Player 接口方法很多且随版本增减，
 * 这里用动态代理只实现用到的方法，其余返回类型默认值，升级 Media3 也不用跟着改。
 */

/** 按返回类型给出默认值（基本类型不能返回 null）。 */
private fun defaultValue(method: Method): Any? = when (method.returnType) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Character.TYPE -> 0.toChar()
    else -> null
}

private inline fun <reified T> proxy(crossinline handle: (Method, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        val a = args ?: emptyArray()
        when (method.name) {
            "equals" -> self === a[0]
            "hashCode" -> System.identityHashCode(self)
            "toString" -> T::class.java.simpleName + "Fake"
            else -> handle(method, a)
        }
    } as T

/** 内层输出端：记录收到的每块数据（原对象与解出的立体声浮点），可限制每次只吃一部分来模拟半块写入。 */
@OptIn(UnstableApi::class)
internal class RecordingAudioSink(private val format: AutoMixPcmFormat) {
    /** 收到的数据块原对象，用来判断是否原样直通。 */
    val buffers = mutableListOf<ByteBuffer>()
    /** 实际吃下的帧（立体声交错浮点）。 */
    val samples = ArrayList<Float>()
    /** 每次最多吃多少字节；null 表示全吃。 */
    var acceptBytes: Int? = null
    var positionUs: Long = AudioSink.CURRENT_POSITION_NOT_SET
    val configured = mutableListOf<Format>()
    var flushes = 0

    val frames: Int get() = samples.size / 2
    fun left(frame: Int): Float = samples[frame * 2]

    val sink: AudioSink = proxy { method, args ->
        when (method.name) {
            "handleBuffer" -> {
                val buffer = args[0] as ByteBuffer
                buffers += buffer
                val n = minOf(buffer.remaining(), acceptBytes ?: buffer.remaining())
                val take = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                take.limit(take.position() + n)
                val count = n / format.bytesPerFrame
                val out = FloatArray(count * 2)
                readPcmFrames(take, format, count, out)
                out.forEach(samples::add)
                buffer.position(buffer.position() + n)
                !buffer.hasRemaining()
            }
            "getCurrentPositionUs" -> positionUs
            "configure" -> { configured += args[0] as Format; Unit }
            "flush" -> { flushes++; Unit }
            "supportsFormat" -> true
            "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            else -> defaultValue(method)
        }
    }
}

/** 输出端回调的记录。 */
internal class RecordingSinkListener : AutoMixAudioSink.Listener {
    val events = mutableListOf<String>()
    override fun onClipStarted() { events += "started" }
    override fun onClipWritten() { events += "written" }
    override fun onAborted(reason: String) { events += "aborted:$reason" }
}

/** 立体声浮点 PCM 的 Media3 格式。 */
@OptIn(UnstableApi::class)
internal fun autoMixRawFormat(format: AutoMixPcmFormat): Format = Format.Builder()
    .setSampleMimeType(MimeTypes.AUDIO_RAW)
    .setSampleRate(format.sampleRate)
    .setChannelCount(format.channels)
    .setPcmEncoding(format.encoding)
    .build()

/** 按帧生成 PCM 块：value(第几帧) 给出左右声道的值。 */
internal fun autoMixPcmBuffer(format: AutoMixPcmFormat, frames: Int, value: (Int) -> Float): ByteBuffer {
    val src = FloatArray(frames * 2) { value(it / 2) }
    val out = ByteBuffer.allocate(frames * format.bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
    writePcmFrames(src, 0, frames, format, out)
    out.flip()
    return out
}

/** 只实现控制器用到的方法的假播放器；条目的排程标记按条目对象登记，替换条目时沿用（与 buildUpon 保留 tag 一致）。 */
internal class FakeAutoMixPlayer {
    val items = mutableListOf<MediaItem>()
    val tags = IdentityHashMap<MediaItem, Any?>()
    var currentIndex = 0
    var positionMs = 0L
    var state = Player.STATE_READY
    val seeks = mutableListOf<Pair<Int, Long>>()

    val ids: List<String> get() = items.map { it.mediaId }
    fun tagOf(item: MediaItem): Any? = tags[item]

    private fun removeRange(from: Int, to: Int) {
        repeat(to - from) { items.removeAt(from) }
        if (currentIndex >= to) currentIndex -= to - from
        else if (currentIndex >= from) currentIndex = from.coerceAtMost(items.size - 1).coerceAtLeast(0)
    }

    val player: Player = proxy { method, args ->
        when (method.name) {
            "getPlaybackState" -> state
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getCurrentMediaItem" -> items.getOrNull(currentIndex)
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args[0] as Int]
            "getCurrentMediaItemIndex" -> currentIndex
            "getCurrentPosition" -> positionMs
            "addMediaItem" -> {
                if (args.size == 1) items += args[0] as MediaItem else items.add(args[0] as Int, args[1] as MediaItem)
                Unit
            }
            "removeMediaItem" -> { val i = args[0] as Int; removeRange(i, i + 1); Unit }
            "removeMediaItems" -> { removeRange(args[0] as Int, args[1] as Int); Unit }
            "replaceMediaItem" -> {
                val i = args[0] as Int
                val next = args[1] as MediaItem
                tags[next] = tags[items[i]]
                items[i] = next
                Unit
            }
            "seekTo" -> {
                if (args.size == 2) { currentIndex = args[0] as Int; positionMs = args[1] as Long; seeks += currentIndex to positionMs }
                else positionMs = args[0] as Long
                Unit
            }
            else -> defaultValue(method)
        }
    }
}

internal fun autoMixTestTrack(id: String, durationMs: Long = 240_000L) = MusicTrack(
    id = id, source = MusicSource.LOCAL, title = id, artists = "", album = "", durationMs = durationMs,
    artworkStart = 0L, artworkEnd = 0L, artworkMark = "", previewUrl = "file:///$id.mp3",
)

internal fun autoMixTestResolved(track: MusicTrack) = ResolvedPlayback(
    track, PlaybackSource(track.previewUrl, AudioQuality.STANDARD, AudioQuality.STANDARD, 320, "mp3", trial = false),
)

/** 单测用的过渡方案：片段格式取 1 kHz，帧号与毫秒一一对应，方便核对。 */
internal val AUTOMIX_TEST_FORMAT = AutoMixPcmFormat(1_000, 2, C.ENCODING_PCM_FLOAT)

internal fun autoMixTestPrepared(
    from: MusicTrack,
    to: ResolvedPlayback,
    clipStartUs: Long = 200_000_000L,
    aDryUntilUs: Long = 8_000_000L,
    totalMs: Long = 14_000L,
): AutoMixPrepared {
    val frames = (totalMs * AUTOMIX_TEST_FORMAT.sampleRate / 1000L).toInt()
    val points = (totalMs / 10L).toInt() + 2
    val map = DoubleArray(points) { it * 0.01 }
    val job = AutoMixSinkJob(AUTOMIX_TEST_FORMAT, FloatArray(frames * 2), clipStartUs, aDryUntilUs, map)
    return AutoMixPrepared(from, to, AutoMixKind.BEAT_MATCH, "test", job, bStartUs = 10_000_000L,
        enterMs = 2_000L, seamMs = 8_000L, fromEndMs = 12_000L, totalMs = totalMs,
        mapA = map, mapB = map, visual = DoubleArray(points) { it.toDouble() / (points - 1) },
        aFirstMediaUs = 0L, bFirstMediaUs = 0L)
}
