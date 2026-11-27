package com.musicone

import android.content.Context
import com.musicone.nativeaudio.automix.AutoMixNative
import com.musicone.nativeaudio.automix.AutoMixVoxInput
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 人声检测用的 YAMNet（LiteRT）。模型已改为同时输出 1024 维嵌入（输出 1），
 * 帧划分与实验代码 yamnet_frames 一致：窗长 15600、帧移 8000（16kHz）。
 */
internal class AutoMixYamnet private constructor(private val interpreter: Interpreter) {
    private val input = ByteBuffer.allocateDirect(AutoMixNative.YAMNET_WINDOW * 4).order(ByteOrder.nativeOrder())
    private val scores = Array(1) { FloatArray(SCORE_CLASSES) }
    // 嵌入层（layer28/reduce_mean）是 INT8 量化张量；实验端直接读取整数原值（-128～127）训练判别头，这里同样不反量化
    private val embedding = Array(1) { Array(1) { Array(1) { ByteArray(EMBEDDING) } } }

    /** 返回逐帧嵌入（帧数 × 1024，行优先）与帧数。输入留在原生内存，逐窗口拷进推理缓冲。 */
    fun embed(m16: AutoMixVoxInput): Pair<FloatArray, Int> {
        val window = AutoMixNative.YAMNET_WINDOW
        val hop = AutoMixNative.YAMNET_HOP
        val length = m16.length
        val frames = if (length < window) 0 else (length - window) / hop + 1
        val out = FloatArray(frames * EMBEDDING)
        val outputs = mapOf<Int, Any>(0 to scores, 1 to embedding)
        for (i in 0 until frames) {
            input.clear()
            check(m16.window(i * hop, window, input)) { "YAMNet 输入窗口越界" }
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
            val row = embedding[0][0][0]
            val base = i * EMBEDDING
            for (k in 0 until EMBEDDING) out[base + k] = row[k].toFloat()
        }
        return out to frames
    }

    companion object {
        private const val EMBEDDING = 1024
        private const val SCORE_CLASSES = 521

        @Volatile private var shared: AutoMixYamnet? = null
        @Volatile private var sharedHead: ByteArray? = null

        /**
         * 进程内复用同一个推理器：模型压缩打包，每次重新读取都要解压 4 MB。
         * 只在 autoMixAnalysisDispatcher 上调用，推理线程随之继承后台优先级。
         */
        fun shared(context: Context): AutoMixYamnet = shared ?: open(context).also { shared = it }

        private fun open(context: Context): AutoMixYamnet {
            // 使用 .bin 后缀绕开 AGP 默认对 .tflite 的不压缩规则；此处按流整块读入内存，不依赖未压缩 mmap。
            val bytes = context.assets.open("automix/yamnet_emb.bin").use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
            // 只用 CPU 推理、不挂 GPU/NNAPI 委托，数值尽量贴近桌面端 TFLite 参考
            // 两个推理线程：分析不赶时间，给界面留出核心
            val options = Interpreter.Options().setNumThreads(2)
            return AutoMixYamnet(Interpreter(model, options))
        }

        fun head(context: Context): ByteArray = sharedHead
            ?: context.assets.open("automix/MusicOneVoxGap_head.bin").use { it.readBytes() }.also { sharedHead = it }
    }
}
