package com.musicone

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 自然过渡分析专用的单个后台线程（后台优先级）。解码、人声检测、混音渲染都在这里串行执行，
 * 由它创建的 LiteRT 推理线程同样继承后台优先级，切歌动画与界面渲染优先拿到 CPU。
 * 人声检测模型也只在这个线程上使用，无需额外加锁。
 */
internal val autoMixAnalysisDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "AutoMixAnalysis").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
