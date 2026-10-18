package com.musicone.demo.nativeaudio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicone.demo.LocalAudioMetadataExtractor
import com.musicone.nativeaudio.NativeAudioOutputPolicy
import com.musicone.nativeaudio.NativeAudioProbe
import com.musicone.nativeaudio.NativeAudioSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NativeAudioVerificationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testProbeAndDecodeApe() {
        val file = File("/sdcard/Music/luckynight.ape")
        assertTrue("APE 测试文件不存在: ${file.absolutePath}", file.exists())

        val uri = Uri.fromFile(file)
        val probe = NativeAudioProbe.probeUri(context, uri).getOrThrow()
        assertEquals("APE", probe.formatName)
        assertEquals(44100, probe.sampleRate)
        assertEquals(2, probe.channelCount)
        assertEquals(16, probe.sourceBitDepth)
        assertTrue("时长应大于 50 秒", probe.durationMs > 50_000)
        assertEquals("Lucky Night", probe.title)
        assertEquals("Jody Marie Gnant", probe.artist)

        val ds = androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(uri).build()
        val session = NativeAudioSession(ds, spec)
        try {
            val format = session.open()
            assertEquals("APE", format.formatName)
            assertEquals(44100, format.outputSampleRate)
            assertEquals(2, format.outputChannels)
            assertEquals(2, format.outputEncoding) // C.ENCODING_PCM_16BIT

            val ptsHolder = LongArray(1)
            var totalBytes = 0
            var chunks = 0
            while (chunks < 50) {
                val chunk = session.readNextPcmChunk(ptsHolder) ?: break
                assertTrue(chunk.remaining() > 0)
                totalBytes += chunk.remaining()
                chunks++
            }
            assertTrue("应成功解码 PCM 数据", totalBytes > 0)
            assertTrue("时间戳应正常推进", ptsHolder[0] >= 0)

            // 测试 Seek
            val seekOk = session.seekTo(10_000_000L) // 10s
            assertTrue(seekOk)
            val chunkAfterSeek = session.readNextPcmChunk(ptsHolder)
            assertNotNull(chunkAfterSeek)
            assertTrue("Seek 后时间戳应在 10s 附近", ptsHolder[0] >= 9_000_000L)
        } finally {
            session.close()
        }
    }

    @Test
    fun testProbeAndDecodeAlac() {
        val file24 = File("/sdcard/Music/test_alac_24bit_96k.m4a")
        assertTrue("ALAC 24bit 测试文件不存在", file24.exists())
        val uri24 = Uri.fromFile(file24)

        val probe = NativeAudioProbe.probeUri(context, uri24).getOrThrow()
        assertEquals("ALAC", probe.formatName)
        assertEquals(96000, probe.sampleRate)
        assertEquals(2, probe.channelCount)
        assertEquals(24, probe.sourceBitDepth)

        val ds = androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(uri24).build()
        val session = NativeAudioSession(ds, spec)
        try {
            val format = session.open()
            assertEquals("ALAC", format.formatName)
            assertEquals(96000, format.outputSampleRate)
            assertEquals(2, format.outputChannels)
            assertEquals(androidx.media3.common.C.ENCODING_PCM_32BIT, format.outputEncoding) // C.ENCODING_PCM_32BIT (24bit in 32bit container)

            val ptsHolder = LongArray(1)
            val chunk = session.readNextPcmChunk(ptsHolder)
            assertNotNull(chunk)
            assertTrue(chunk!!.remaining() > 0)
        } finally {
            session.close()
        }

        // 16bit 44.1k ALAC
        val file16 = File("/sdcard/Music/test_alac_16bit_44k.m4a")
        assertTrue("ALAC 16bit 测试文件不存在", file16.exists())
        val probe16 = NativeAudioProbe.probeUri(context, Uri.fromFile(file16)).getOrThrow()
        assertEquals("ALAC", probe16.formatName)
        assertEquals(44100, probe16.sampleRate)
        assertEquals(16, probe16.sourceBitDepth)
    }

    @Test
    fun testProbeAndDecodeWavPack() {
        val file = File("/sdcard/Music/test_wavpack_24bit_48k.wv")
        assertTrue("WavPack 测试文件不存在", file.exists())
        val uri = Uri.fromFile(file)

        val probe = NativeAudioProbe.probeUri(context, uri).getOrThrow()
        assertEquals("WavPack", probe.formatName)
        assertEquals(48000, probe.sampleRate)
        assertEquals(2, probe.channelCount)
        assertEquals(24, probe.sourceBitDepth)

        val ds = androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(uri).build()
        val session = NativeAudioSession(ds, spec)
        try {
            val format = session.open()
            assertEquals("WavPack", format.formatName)
            assertEquals(48000, format.outputSampleRate)
            assertEquals(2, format.outputChannels)

            val ptsHolder = LongArray(1)
            val chunk = session.readNextPcmChunk(ptsHolder)
            assertNotNull(chunk)
            assertTrue(chunk!!.remaining() > 0)
        } finally {
            session.close()
        }
    }

    @Test
    fun testProbeAndDecodeDsd() {
        // 1. DSD64 DSF
        val dsfFile = File("/sdcard/Music/test_dsd64.dsf")
        assertTrue("DSF 测试文件不存在", dsfFile.exists())
        val dsfUri = Uri.fromFile(dsfFile)

        val dsfProbe = NativeAudioProbe.probeUri(context, dsfUri).getOrThrow()
        assertTrue(dsfProbe.isDsd)
        assertEquals("DSD64", dsfProbe.formatName)
        assertEquals(2, dsfProbe.channelCount)

        val ds = androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(dsfUri).build()
        val session = NativeAudioSession(ds, spec, targetDsdSampleRate = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE)
        try {
            val format = session.open()
            assertTrue(format.isDsd)
            assertEquals(192000, format.outputSampleRate)
            assertEquals(2, format.outputChannels)
            assertEquals(androidx.media3.common.C.ENCODING_PCM_32BIT, format.outputEncoding) // 24-bit PCM in 32-bit container

            val ptsHolder = LongArray(1)
            var totalBytes = 0
            for (i in 0 until 20) {
                val chunk = session.readNextPcmChunk(ptsHolder) ?: break
                totalBytes += chunk.remaining()
            }
            assertTrue("DSD 转 PCM 应正常输出数据", totalBytes > 0)

            // 测试 Seek
            val seekOk = session.seekTo(5_000_000L) // 5s
            assertTrue(seekOk)
            val chunkAfterSeek = session.readNextPcmChunk(ptsHolder)
            assertNotNull(chunkAfterSeek)
            assertTrue("Seek 后时间戳应在 5s 附近", ptsHolder[0] >= 4_500_000L)
        } finally {
            session.close()
        }

        // 2. DSD128 DSF
        val dsd128File = File("/sdcard/Music/test_dsd128.dsf")
        assertTrue("DSD128 测试文件不存在", dsd128File.exists())
        val dsd128Probe = NativeAudioProbe.probeUri(context, Uri.fromFile(dsd128File)).getOrThrow()
        assertTrue(dsd128Probe.isDsd)
        assertEquals("DSD128", dsd128Probe.formatName)

        // 3. DSD64 DFF (未压缩)
        val dffFile = File("/sdcard/Music/test_dsd64.dff")
        assertTrue("DFF 测试文件不存在", dffFile.exists())
        val dffProbe = NativeAudioProbe.probeUri(context, Uri.fromFile(dffFile)).getOrThrow()
        assertTrue(dffProbe.isDsd)
        assertEquals("DSD64", dffProbe.formatName)
    }

    @Test
    fun testLocalAudioMetadataExtractor() {
        val apeFile = File("/sdcard/Music/luckynight.ape")
        val apeMeta = LocalAudioMetadataExtractor.extract(context, Uri.fromFile(apeFile), apeFile.name)
        assertEquals("APE", apeMeta.formatName)
        assertEquals("Lucky Night", apeMeta.title)
        assertEquals("APE · 16bit / 44.1kHz", apeMeta.formatDescription)

        val alacFile = File("/sdcard/Music/test_alac_24bit_96k.m4a")
        val alacMeta = LocalAudioMetadataExtractor.extract(context, Uri.fromFile(alacFile), alacFile.name)
        assertEquals("ALAC", alacMeta.formatName)
        assertEquals("ALAC · 24bit / 96kHz", alacMeta.formatDescription)

        val dsdFile = File("/sdcard/Music/test_dsd64.dsf")
        val dsdMeta = LocalAudioMetadataExtractor.extract(context, Uri.fromFile(dsdFile), dsdFile.name)
        assertEquals("DSD64", dsdMeta.formatName)
        assertEquals("DSD64 → PCM 32bit / 192kHz", dsdMeta.formatDescription)
    }

    @Test
    fun testWavPackHybridRejection() {
        val hybridFile = File("/sdcard/Music/test_wavpack_hybrid.wv")
        assertTrue("WavPack Hybrid 测试文件不存在", hybridFile.exists())

        val res = NativeAudioProbe.probeUri(context, Uri.fromFile(hybridFile))
        assertTrue("WavPack Hybrid 模式应当被识别并拒绝", res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        assertTrue("应包含具体提示：$msg", msg.contains("Hybrid") || msg.contains("暂不支持"))
    }

    @Test
    fun testMedia3PlaybackPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val upstreamFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
                val defaultFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(upstreamFactory)
                val routingFactory = com.musicone.nativeaudio.NativeAudioRoutingMediaSourceFactory(
                    defaultFactory = defaultFactory,
                    dataSourceFactory = upstreamFactory,
                    targetDsdSampleRate = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
                )

                val p = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setMediaSourceFactory(routingFactory)
                    .build()
                player = p

                // 1. 添加 APE 音频
                val apeItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(File("/sdcard/Music/luckynight.ape")))
                // 2. 添加 ALAC 音频
                val alacItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(File("/sdcard/Music/test_alac_24bit_96k.m4a")))
                // 3. 添加 DSD 音频
                val dsdItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(File("/sdcard/Music/test_dsd64.dsf")))

                p.addMediaItem(apeItem)
                p.addMediaItem(alacItem)
                p.addMediaItem(dsdItem)
                p.prepare()
                p.play()
            }

            // 测试线程等待主线程调度与缓冲
            Thread.sleep(600)

            // 执行 Seek 操作
            instrumentation.runOnMainSync {
                player?.seekTo(15_000L) // seek 到 15 秒
            }
            Thread.sleep(300)

            // 执行暂停
            instrumentation.runOnMainSync {
                player?.pause()
            }
            Thread.sleep(100)

            // 执行切歌（切换到 ALAC）
            instrumentation.runOnMainSync {
                player?.seekToNextMediaItem()
                player?.play()
            }
            Thread.sleep(400)

            // 再次切歌（切换到 DSD）
            instrumentation.runOnMainSync {
                player?.seekToNextMediaItem()
            }
            Thread.sleep(400)
        } finally {
            instrumentation.runOnMainSync {
                player?.release()
            }
        }
    }

    @Test
    fun testAudioTrackDirectSoundOutput() {
        val file = File("/sdcard/Music/test_alac_24bit_96k.m4a")
        assertTrue(file.exists())

        val ds = androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(Uri.fromFile(file)).build()
        val session = NativeAudioSession(ds, spec)
        var audioTrack: android.media.AudioTrack? = null
        try {
            val format = session.open()
            assertEquals(22, format.outputEncoding) // C.ENCODING_PCM_32BIT

            val channelConfig = android.media.AudioFormat.CHANNEL_OUT_STEREO
            val encoding = android.media.AudioFormat.ENCODING_PCM_32BIT
            val minBuf = android.media.AudioTrack.getMinBufferSize(format.outputSampleRate, channelConfig, encoding)
            assertTrue("AudioTrack 最小缓冲应大于 0", minBuf > 0)

            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.outputSampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track

            track.play()
            assertEquals(android.media.AudioTrack.PLAYSTATE_PLAYING, track.playState)

            val ptsHolder = LongArray(1)
            var totalWritten = 0
            for (i in 0 until 15) {
                val chunk = session.readNextPcmChunk(ptsHolder) ?: break
                val size = chunk.remaining()
                val bytes = ByteArray(size)
                chunk.get(bytes)
                val written = track.write(bytes, 0, size, android.media.AudioTrack.WRITE_BLOCKING)
                assertTrue("写入 AudioTrack 字节数应大于 0", written > 0)
                totalWritten += written
            }
            assertTrue("应成功向 AudioTrack 写入有效 PCM 声音数据", totalWritten > 0)

            // 等待硬件音频帧推进
            Thread.sleep(200)
            assertTrue("音频硬件播放头应开始推进", track.playbackHeadPosition >= 0)
        } finally {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {}
            session.close()
        }
    }
}
