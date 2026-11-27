package com.musicone.nativeaudio.automix

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** 自然过渡引擎（C++，忠实移植自实验算法 vox_mix.py）的 JNI 入口。所有调用都很重，只能在后台线程执行。 */
@Keep
object AutoMixNative {
    /** 分析与渲染统一采样率，与实验代码 WORK_SR 一致。 */
    const val WORK_SAMPLE_RATE = 44_100
    /** 分析窗口：A 最后 75 秒、B 前 75 秒。 */
    const val WINDOW_SECONDS = 75.0
    /** YAMNet 帧长（16kHz 样本）与帧移。 */
    const val YAMNET_WINDOW = 15_600
    const val YAMNET_HOP = 8_000

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("musicone-automix")
                loaded = true
            }
        }
    }

    /**
     * 跑完整决策与渲染。a 为 A 尾段存储（首帧在原曲 a.offset 处），b 为 B 头段存储；
     * 两个存储的数据移交给引擎，调用后为空，但仍须照常 close。片段按 outRate 输出（即 A 在播放链路里的采样率）。
     */
    fun mix(a: AutoMixPcmStore, aTotal: Long, b: AutoMixPcmStore, bTotal: Long,
            embA: FloatArray, framesA: Int, embB: FloatArray, framesB: Int, head: ByteArray, outRate: Int): AutoMixNativeResult? {
        ensureLoaded()
        return nativeMix(a.handle(), aTotal, b.handle(), bTotal, embA, framesA, embB, framesB, head, outRate, AutoMixNativeResult())
    }

    // 以下句柄接口仅供 AutoMixPcmStore / AutoMixVoxInput 调用；不能用 internal（JVM 上会改名，JNI 找不到）
    external fun nativeStoreCreate(inRate: Int, keepTail: Boolean, seconds: Double): Long
    external fun nativeStorePush(handle: Long, pcm: FloatArray, frames: Int)
    external fun nativeStoreHeadComplete(handle: Long): Boolean
    external fun nativeStoreFinish(handle: Long)
    external fun nativeStoreOffset(handle: Long): Long
    external fun nativeStoreFrames(handle: Long): Long
    external fun nativeStoreTotalOut(handle: Long, nativeFrames: Long): Long
    external fun nativeStoreRelease(handle: Long)
    external fun nativeVoxCreate(store: Long, startFrame: Long, frames: Long): Long
    external fun nativeVoxLength(vox: Long): Int
    external fun nativeVoxWindow(vox: Long, offset: Int, window: Int, dst: ByteBuffer): Boolean
    external fun nativeVoxRelease(vox: Long)
    private external fun nativeMix(aStore: Long, aTotal: Long, bStore: Long, bTotal: Long,
                                   embA: FloatArray, framesA: Int, embB: FloatArray, framesB: Int, head: ByteArray,
                                   outRate: Int, result: AutoMixNativeResult): AutoMixNativeResult?
}

/**
 * 离线解码的 44.1kHz 立体声 PCM，存放在原生内存（不占 Java 堆，分析时不触发阻塞式回收）。
 * 按原始采样率追加，内部流式分块重采样；keepTail 时只保留最后 seconds 秒，否则攒够前 seconds 秒即可停。
 * 方法都加锁：解码线程追加与分析线程释放可能交错，释放后追加直接忽略。
 */
@Keep
class AutoMixPcmStore(inRate: Int, keepTail: Boolean, seconds: Double) : AutoCloseable {
    private var handle: Long

    init {
        AutoMixNative.ensureLoaded()
        handle = AutoMixNative.nativeStoreCreate(inRate, keepTail, seconds)
    }

    @Synchronized fun handle(): Long = handle.also { check(it != 0L) { "PCM 存储已释放" } }

    /** 追加 frames 帧立体声交错浮点（原始采样率）。 */
    @Synchronized fun push(pcm: FloatArray, frames: Int) {
        if (handle != 0L && frames > 0) AutoMixNative.nativeStorePush(handle, pcm, frames)
    }

    @get:Synchronized val headComplete: Boolean get() = handle != 0L && AutoMixNative.nativeStoreHeadComplete(handle)

    /** 输入结束：剩余输入出块。 */
    @Synchronized fun finish() { if (handle != 0L) AutoMixNative.nativeStoreFinish(handle) }

    /** 存储首帧在整首 44.1kHz 序列里的帧号。 */
    @get:Synchronized val offset: Long get() = AutoMixNative.nativeStoreOffset(handle())
    @get:Synchronized val frames: Long get() = AutoMixNative.nativeStoreFrames(handle())
    /** 整首在 44.1kHz 下的帧数（按原始帧数换算）。 */
    @Synchronized fun totalOut(nativeFrames: Long): Long = AutoMixNative.nativeStoreTotalOut(handle(), nativeFrames)

    /** 取存储内 [startFrame, startFrame + frames) 一段，转成 YAMNet 输入（16kHz 单声道）。 */
    @Synchronized fun voxInput(startFrame: Long, frames: Long): AutoMixVoxInput =
        AutoMixVoxInput(AutoMixNative.nativeVoxCreate(handle(), startFrame, frames))

    @Synchronized override fun close() {
        if (handle != 0L) { AutoMixNative.nativeStoreRelease(handle); handle = 0L }
    }
}

/** 原生内存里的 YAMNet 输入（16kHz 单声道），按窗口拷进推理输入缓冲；用完 close。 */
@Keep
class AutoMixVoxInput(private var handle: Long) : AutoCloseable {
    val length: Int get() = if (handle == 0L) 0 else AutoMixNative.nativeVoxLength(handle)

    /** 把 [offset, offset + window) 拷进直接缓冲 dst（本机字节序浮点）。 */
    fun window(offset: Int, window: Int, dst: ByteBuffer): Boolean =
        handle != 0L && AutoMixNative.nativeVoxWindow(handle, offset, window, dst)

    override fun close() {
        if (handle != 0L) { AutoMixNative.nativeVoxRelease(handle); handle = 0L }
    }
}

/**
 * 引擎输出。时间单位为秒：clipStartA、seamA 为 A 原曲时间，bCue、bEndSrc 为 B 原曲时间，其余为片段时间（片段首样本为 0）。
 * kind：0 不过渡（无节拍等），1 变速对拍，2 鼓点衔接，3 回声退场，4 淡出淡入。mapA / mapB / visual 每 10ms 一个点。
 */
@Keep
class AutoMixNativeResult {
    @JvmField var kind: Int = 0
    @JvmField var branch: String = ""
    @JvmField var log: String = ""
    @JvmField var seamA: Double = Double.NaN
    @JvmField var bCue: Double = Double.NaN
    @JvmField var overlapBars: Int = -1
    @JvmField var rateA: Double = Double.NaN
    @JvmField var rateB: Double = Double.NaN
    @JvmField var clip: FloatArray? = null
    @JvmField var clipStartA: Double = 0.0
    @JvmField var enter: Double = 0.0
    @JvmField var seam: Double = 0.0
    @JvmField var fromEnd: Double = 0.0
    @JvmField var total: Double = 0.0
    @JvmField var aDryUntil: Double = 0.0
    @JvmField var bEndSrc: Double = 0.0
    @JvmField var mapA: DoubleArray? = null
    @JvmField var mapB: DoubleArray? = null
    @JvmField var visual: DoubleArray? = null
}
