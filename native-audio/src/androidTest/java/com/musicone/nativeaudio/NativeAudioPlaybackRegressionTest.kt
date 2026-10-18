package com.musicone.nativeaudio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeAudioPlaybackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val factory = DefaultDataSource.Factory(context)

    @Test
    fun dsdContinuesAcrossRoundsAndRestartsAtZeroAfterCancellation() {
        val file = createDsf()
        var session: NativeAudioSession? = null
        try {
            val spec = DataSpec.Builder().setUri(Uri.fromFile(file)).build()
            var endUs = 0L
            repeat(4) { generation ->
                val round = NativeAudioDecoderLoadable(context, factory, spec, NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
                    null, generation, session, endUs, 256 * 1024)
                round.load()
                session = round.session
                assertNotNull(round.sourceInfo)
                assertTrue("每轮都应输出 PCM", round.chunks.isNotEmpty())
                assertTrue("续播时间戳必须紧接上轮末端：$endUs / ${round.chunks.first().timeUs}",
                    kotlin.math.abs(round.chunks.first().timeUs - endUs) <= 2)
                assertTrue(round.endPositionUs > endUs)
                assertTrue(round.chunks.sumOf { it.data.size } <= 256 * 1024)
                endUs = round.endPositionUs
            }
            session!!.cancel()
            val restart = NativeAudioDecoderLoadable(context, factory, spec, NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
                0L, 5, session, 0L, 256 * 1024)
            restart.load()
            assertTrue("取消后跳回开头仍应输出 PCM", restart.chunks.any { it.data.isNotEmpty() })
            assertTrue("跳回开头的时间戳应归零", restart.chunks.first().timeUs in 0L..1_000L)
        } finally {
            session?.close()
            file.delete()
        }
    }

    @Test
    fun dsdMedia3PlaysContinuouslySeeksToZeroAndEnds() = exerciseDsdPlayback(1f)

    @Test
    fun dsdMedia3DoubleSpeedStartsAndSeeksToZero() = exerciseDsdPlayback(2f)

    private fun exerciseDsdPlayback(speed: Float) {
        val file = createDsf()
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            val routing = NativeAudioRoutingMediaSourceFactory(
                DefaultMediaSourceFactory(factory), factory, context = context,
            )
            player = ExoPlayer.Builder(context).setMediaSourceFactory(routing).build().apply {
                volume = 0f
                setPlaybackSpeed(speed)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
        try {
            awaitPlayer(player, failure) { it.currentPosition >= 2_200L }
            var previous = 0L
            repeat(12) {
                instrumentation.runOnMainSync {
                    assertTrue("顺序播放进度不能回退", player.currentPosition >= previous)
                    previous = player.currentPosition
                }
                Thread.sleep(30)
            }
            instrumentation.runOnMainSync { player.seekTo(0) }
            awaitPlayer(player, failure) { it.playbackState == Player.STATE_READY && it.currentPosition in 300L..1_500L }
            instrumentation.runOnMainSync {
                player.seekTo(1_500)
                player.seekTo(0)
                player.seekTo(3_000)
            }
            awaitPlayer(player, failure) { it.playbackState == Player.STATE_READY && it.currentPosition >= 3_200 }
            awaitPlayer(player, failure) { it.playbackState == Player.STATE_ENDED }
            instrumentation.runOnMainSync { player.seekTo(0); player.play() }
            awaitPlayer(player, failure) { it.playbackState == Player.STATE_READY && it.currentPosition in 300L..1_500L }
            instrumentation.runOnMainSync { assertTrue("时长应在播放线程发布", player.duration in 5_900L..6_100L) }
        } finally {
            instrumentation.runOnMainSync { player.release() }
            file.delete()
        }
    }

    @Test
    fun aiffPreservesIntegerSamplesAndRoutesByContent() {
        for ((bits, compression) in listOf(8 to null, 16 to null, 24 to null, 32 to null, 16 to "NONE", 16 to "sowt")) {
            val file = createAiff(bits, compression)
            try {
                val probe = NativeAudioProbe.probeUri(context, Uri.fromFile(file)).getOrThrow()
                assertEquals("AIFF", probe.formatName)
                assertEquals(bits, probe.sourceBitDepth)
                val spec = DataSpec.Builder().setUri(Uri.fromFile(file)).build()
                NativeAudioSession(factory.createDataSource(), spec, context = context).use { session ->
                    val info = session.open()
                    assertEquals(44100, info.outputSampleRate)
                    assertEquals(2, info.outputChannels)
                    assertEquals(bits, info.sourceBitDepth)
                    val buffer = session.readNextPcmChunk(LongArray(1))!!.order(ByteOrder.LITTLE_ENDIAN)
                    repeat(16) { index ->
                        val sample = when (bits) {
                            8 -> buffer.short.toInt() shr 8
                            16 -> buffer.short.toInt()
                            24 -> buffer.int shr 8
                            else -> buffer.int
                        }
                        val expected = if (bits == 8) index * 7 - 50 else index * 101 - 700
                        assertEquals("整数 PCM 不应改写", expected, sample)
                    }
                }
                instrumentation.runOnMainSync {
                    val routing = NativeAudioRoutingMediaSourceFactory(
                        DefaultMediaSourceFactory(factory), factory, context = context,
                    )
                    assertTrue("无后缀提示时也必须识别 AIFF 内容",
                        routing.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))) is NativeAudioMediaSource)
                }
            } finally { file.delete() }
        }
    }

    @Test
    fun aiffMedia3ReachesEnd() {
        val file = createAiff(24)
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(
                NativeAudioRoutingMediaSourceFactory(DefaultMediaSourceFactory(factory), factory, context = context),
            ).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
        try { awaitPlayer(player, failure) { it.playbackState == Player.STATE_ENDED } }
        finally {
            instrumentation.runOnMainSync { player.release() }
            file.delete()
        }
    }

    private fun awaitPlayer(player: ExoPlayer, failure: AtomicReference<PlaybackException?>, condition: (ExoPlayer) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var state = ""
        while (System.nanoTime() < deadline) {
            failure.get()?.let { throw AssertionError("Media3 播放失败", it) }
            var done = false
            instrumentation.runOnMainSync {
                done = condition(player)
                state = "state=${player.playbackState}, position=${player.currentPosition}, buffered=${player.bufferedPosition}, duration=${player.duration}"
            }
            if (done) return
            Thread.sleep(25)
        }
        fail("等待播放状态超时：$state")
    }

    private fun createDsf(): File {
        val sampleCount = 2_822_400L * 6
        val blockSize = 4096
        val payloadSize = ((sampleCount / 8 + blockSize - 1) / blockSize * blockSize * 2).toInt()
        val buffer = ByteBuffer.allocate(92 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("DSD ".toByteArray()).putLong(28).putLong(buffer.capacity().toLong()).putLong(0)
        buffer.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2)
        buffer.putInt(2_822_400).putInt(1).putLong(sampleCount).putInt(blockSize).putInt(0)
        buffer.put("data".toByteArray()).putLong(12L + payloadSize)
        while (buffer.hasRemaining()) buffer.put(0x69.toByte())
        return File.createTempFile("native-dsd-", ".dsf", context.cacheDir).apply { writeBytes(buffer.array()) }
    }

    private fun createAiff(bits: Int, compression: String? = null): File {
        val frames = 44_100
        val bytesPerSample = bits / 8
        val dataSize = frames * 2 * bytesPerSample
        val extra = if (compression == null) 0 else 6
        val buffer = ByteBuffer.allocate(54 + extra + dataSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put("FORM".toByteArray()).putInt(46 + extra + dataSize)
            .put((if (compression == null) "AIFF" else "AIFC").toByteArray())
        buffer.put("COMM".toByteArray()).putInt(18 + extra).putShort(2).putInt(frames).putShort(bits.toShort())
        buffer.put(byteArrayOf(0x40, 0x0e, 0xac.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
        if (compression != null) buffer.put(compression.toByteArray()).putShort(0)
        buffer.put("SSND".toByteArray()).putInt(8 + dataSize).putInt(0).putInt(0)
        if (compression == "sowt") buffer.order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { index ->
            val value = if (bits == 8) (index % 16) * 7 - 50 else (index % 16) * 101 - 700
            when (bits) {
                8 -> buffer.put(value.toByte())
                16 -> buffer.putShort(value.toShort())
                24 -> buffer.put((value shr 16).toByte()).put((value shr 8).toByte()).put(value.toByte())
                else -> buffer.putInt(value)
            }
        }
        // 使用 .audio 后缀同时覆盖备份恢复时的内容识别。
        return File.createTempFile("native-aiff-", ".audio", context.cacheDir).apply { writeBytes(buffer.array()) }
    }
}
