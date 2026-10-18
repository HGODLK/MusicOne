package com.musicone.nativeaudio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 通过 fixtureDir 参数复用本机音频样本，不把外部歌曲加入版本库。 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeAudioLosslessFixtureTest {
    @Test
    fun suppliedLosslessFilesContinueAndSeekZero() {
        val directory = InstrumentationRegistry.getArguments().getString("fixtureDir")
        assumeNotNull(directory)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = DefaultDataSource.Factory(context)
        for (name in listOf("luckynight.ape", "test_alac_24bit_96k.m4a", "test_wavpack_24bit_48k.wv", "test_dsd128.dsf", "test_dsd64.dff")) {
            val file = File(directory!!, name)
            assertTrue("缺少外部测试样本：$name", file.isFile)
            val spec = DataSpec.Builder().setUri(Uri.fromFile(file)).build()
            var session: NativeAudioSession? = null
            var firstBytes: ByteArray? = null
            var endUs = 0L
            try {
                for (roundIndex in 0 until 6) {
                    val round = NativeAudioDecoderLoadable(context, factory, spec, NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
                        null, 0, session, endUs, 256 * 1024)
                    round.load()
                    session = round.session
                    val dataChunks = round.chunks.filter { it.data.isNotEmpty() }
                    assertTrue("$name 未输出 PCM", dataChunks.isNotEmpty())
                    if (firstBytes == null) firstBytes = dataChunks.first().data
                    assertTrue("$name 顺序加载不应回退：$endUs / ${dataChunks.first().timeUs}",
                        kotlin.math.abs(dataChunks.first().timeUs - endUs) <= 2L)
                    assertTrue("$name 每轮缓冲不可越界", dataChunks.sumOf { it.data.size } <= 256 * 1024)
                    endUs = round.endPositionUs
                    if (round.reachedEndOfStream) break
                }
                repeat(3) { generation ->
                    session!!.cancel()
                    val restart = NativeAudioDecoderLoadable(context, factory, spec, NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
                        0L, generation + 1, session, 0L, 256 * 1024)
                    restart.load()
                    assertTrue("$name 跳回开头必须继续出数据", restart.chunks.first().data.isNotEmpty())
                    assertTrue("$name 跳回开头时间戳必须归零", restart.chunks.first().timeUs in 0L..1000L)
                    assertArrayEquals("$name 重新开始的 PCM 必须一致", firstBytes, restart.chunks.first().data)
                }
            } finally { session?.close() }
        }
    }
}
