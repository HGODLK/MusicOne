package com.musicone

import androidx.media3.common.MediaItem
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.source.MediaSource
import com.musicone.nativeaudio.automix.AutoMixNative
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** 分析完成后生成的自然过渡方案，可以直接交给播放器执行。 */
internal class AutoMixPrepared(
    val from: MusicTrack,
    val to: ResolvedPlayback,
    val kind: AutoMixKind,
    val branch: String,
    val job: AutoMixSinkJob,
    /** 下一首（B）加入播放列表时的起播位置（微秒）。 */
    val bStartUs: Long,
    val enterMs: Long,
    val seamMs: Long,
    val fromEndMs: Long,
    val totalMs: Long,
    /** 过渡片段时间到 A/B 原曲时间（秒）和画面渐变进度的映射表，每 10ms 采样一个点。 */
    val mapA: DoubleArray,
    val mapB: DoubleArray,
    val visual: DoubleArray,
    /** A、B 解码出的第一个样本对应的媒体时间偏移（微秒）。 */
    val aFirstMediaUs: Long,
    val bFirstMediaUs: Long,
)

/** 后台执行自然过渡分析：离线解码 A 结尾和 B 开头 -> YAMNet 提取人声特征 -> C++ 引擎算切点并渲染混音片段。 */
internal class AutoMixAnalyzer(private val context: Context, mediaSourceFactory: MediaSource.Factory) {
    private val decoder = AutoMixDecoder(context, mediaSourceFactory)

    /** fromItem 必须是播放器正在播的条目本身（同一地址、缓存键、请求头），保证分析的就是实际听到的文件。 */
    suspend fun prepare(from: MusicTrack, fromItem: MediaItem, to: ResolvedPlayback): AutoMixPrepared? = withContext(autoMixAnalysisDispatcher) {
        val started = System.nanoTime()
        val b = to.track.copy(previewUrl = to.source.url)
        // A 要整首从头解（时间轴与实时播放逐样本一致），B 只解开头；在同一个后台线程上先后进行，不与界面抢核心
        val a0 = decoder.decode(fromItem, keepTail = true, seconds = KEEP_SECONDS, label = "A")
            ?: return@withContext fail("A 解码失败或格式不支持")
        // PCM 存放在原生内存，无论成败都要释放
        a0.use {
            val bd = decoder.decode(autoMixMediaItem(context, b), keepTail = false, seconds = KEEP_SECONDS, label = "B")
                ?: return@withContext fail("B 解码失败或格式不支持")
            bd.use { analyze(started, from, to, a0, bd) }
        }
    }

    private fun analyze(started: Long, from: MusicTrack, to: ResolvedPlayback, a0: AutoMixDecoded, bd: AutoMixDecoded): AutoMixPrepared? {
        val decodedAt = System.nanoTime()
        // 直连解码的输出位深可能和播放链路不同（例如 24 位 FLAC 解成 16 位）；片段必须按实时链路的格式写出
        val a = if (!a0.direct) a0 else {
            val live = AutoMixEngine.sink?.livePcmFormat
            if (live == null || live.sampleRate != a0.format.sampleRate || !live.supported)
                return fail("实时播放格式与解码不一致（$live / ${a0.format}）")
            a0.withFormat(live)
        }
        if (!a.format.supported || !bd.format.supported) return fail("PCM 格式不支持")
        val sr = AutoMixNative.WORK_SAMPLE_RATE
        val window = (AutoMixNative.WINDOW_SECONDS * sr).toLong()
        val aTotal = a.total
        val s0 = maxOf(0L, aTotal - window)
        if (s0 < a.offset) return fail("A 尾段不足")
        // 人声检测只看 A 尾段与 B 开头各一个窗口；直接按区间送进原生层，不在 Java 堆上再拷一份
        val aSegStart = s0 - a.offset
        val bSegFrames = minOf(window, bd.frames)
        val head = AutoMixYamnet.head(context)
        val yamnetAt = System.nanoTime()
        val yamnet = AutoMixYamnet.shared(context)
        val (embA, framesA) = a.pcm.voxInput(aSegStart, a.frames - aSegStart).use(yamnet::embed)
        val (embB, framesB) = bd.pcm.voxInput(0L, bSegFrames).use(yamnet::embed)
        // B 全长：解码只到开头，按源时长估算（仅用于 B 是否够长的判断）；须在 mix 移交数据之前取帧数
        val bTotal = maxOf(bd.frames, (to.track.durationMs.coerceAtLeast(0L) * sr / 1000L))
        val embeddedAt = System.nanoTime()
        Log.i(TAG, "分析耗时：解码 ${(decodedAt - started) / 1_000_000}ms，人声检测 ${(embeddedAt - yamnetAt) / 1_000_000}ms")
        val r = AutoMixNative.mix(a.pcm, aTotal, bd.pcm, bTotal, embA, framesA, embB, framesB, head, a.format.sampleRate)
            ?: return fail("引擎返回空")
        val clip = r.clip
        val kind = when (r.kind) { 1 -> AutoMixKind.BEAT_MATCH; 2 -> AutoMixKind.DRUM_CUT; 3 -> AutoMixKind.ECHO_OUT; 4 -> AutoMixKind.FADE; else -> null }
        if (kind == null || clip == null) return fail("降级为普通切歌：${r.branch}")
        val outRate = a.format.sampleRate
        // A 输入在片段时间 tX 处送完；之后输出端开始吃 B，B 必须从 bEnd − (total − tX) 附近起播
        val aEndS = a.nativeFrames.toDouble() / outRate
        val tX = aEndS - r.clipStartA
        val sB = r.bEndSrc - (r.total - tX) - B_LEAD_S
        val bDecodedS = bd.nativeFrames.toDouble() / bd.format.sampleRate
        when {
            sB < 0.0 -> return fail("B 起播点为负（${"%.2f".format(sB)}s）")
            r.bEndSrc + 1.0 > bDecodedS -> return fail("B 解码长度不足")
            r.total < tX + 0.5 -> return fail("片段短于 A 剩余输入")
            r.aDryUntil < 1.0 -> return fail("切入前原样段过短")
        }
        val us = 1_000_000.0
        val job = AutoMixSinkJob(a.format, clip, a.firstMediaUs + (r.clipStartA * us).roundToLong(),
            (r.aDryUntil * us).roundToLong(), r.mapB ?: DoubleArray(2), bd.firstMediaUs)
        Log.i(TAG, "分析完成 ${r.branch}，片段 ${"%.1f".format(r.total)}s，耗时 ${(System.nanoTime() - started) / 1_000_000}ms\n${r.log}")
        val ms = { s: Double -> (s * 1000).roundToLong().coerceAtLeast(0L) }
        val totalMs = ms(r.total)
        val fromEndMs = ms(r.fromEnd).coerceAtMost(totalMs)
        val seamMs = ms(r.seam).coerceAtMost(fromEndMs)
        return AutoMixPrepared(from, to, kind, r.branch, job, bd.firstMediaUs + (sB * us).roundToLong(),
            ms(r.enter).coerceAtMost(seamMs), seamMs, fromEndMs, totalMs,
            r.mapA ?: DoubleArray(2), r.mapB ?: DoubleArray(2), smoothAutoMixVisual(r.visual ?: DoubleArray(2), r.enter, r.fromEnd),
            a.firstMediaUs, bd.firstMediaUs)
    }

    private fun fail(reason: String): AutoMixPrepared? {
        Log.i(TAG, "自然过渡不可用：$reason")
        return null
    }

    companion object {
        private const val TAG = "MusicOneAutoMix"
        /** 实际保留 80 秒：对照工具验证过 22 组组合的决策与 150 秒输入完全一致。 */
        const val KEEP_SECONDS = 80.0
        /** B 提前起播量：输出端交叉前先缓冲这段，保证实时 B 已解到对齐点。 */
        private const val B_LEAD_S = 0.1
    }
}

/**
 * 画面用的渐变曲线：引擎给的是 B 的中高频占比，鼓点衔接／回声退场在切点是台阶，画面会“渐变一半突变”。
 * 用最长 4 秒的滑动平均把台阶摊成斜坡（单调性不变），只影响背景与封面，不影响声音；首尾由界面按进场与收干净重新归一化。
 */
internal fun smoothAutoMixVisual(visual: DoubleArray, enterS: Double, fromEndS: Double): DoubleArray {
    if (visual.size < 3) return visual
    val half = (minOf(4.0, (fromEndS - enterS).coerceAtLeast(0.0) / 2.0) / 0.01 / 2.0).toInt()
    if (half < 1) return visual
    val prefix = DoubleArray(visual.size + 1)
    for (i in visual.indices) prefix[i + 1] = prefix[i] + visual[i]
    return DoubleArray(visual.size) { i ->
        val lo = i - half
        val hi = i + half
        // 越界部分按两端值延伸：起点前为 0、终点后为 1
        var sum = prefix[minOf(hi, visual.size - 1) + 1] - prefix[maxOf(lo, 0)]
        if (lo < 0) sum += visual.first() * -lo
        if (hi > visual.size - 1) sum += visual.last() * (hi - visual.size + 1)
        sum / (2 * half + 1)
    }
}
