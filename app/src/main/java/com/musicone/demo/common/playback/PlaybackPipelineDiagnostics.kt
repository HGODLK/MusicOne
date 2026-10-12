package com.musicone.demo

import android.util.Log

/** 只记录请求代次和阶段，不记录音源地址、歌词或账号信息。 */
internal object PlaybackPipelineDiagnostics {
    fun stage(name: String, generation: Long, elapsedMs: Long? = null) {
        Log.i("MusicPlaybackPipeline", "阶段=$name 代次=$generation 耗时毫秒=${elapsedMs ?: -1}")
    }
}

/** USB 写线程每五秒汇总一次供数间隔和写入耗时，不逐包打印。 */
internal class UsbPlaybackDiagnostics(private val sampleRate: Int) {
    private var lastReport = System.nanoTime()
    private var previousEnd = 0L
    private var longestGap = 0L
    private var longestWrite = 0L
    private var writes = 0
    @Volatile private var resetPending = false

    fun written(start: Long, end: Long, bufferedFrames: Long, pendingFrames: Long) {
        if (resetPending) { reset(); resetPending = false }
        if (previousEnd != 0L) longestGap = maxOf(longestGap, start - previousEnd)
        longestWrite = maxOf(longestWrite, end - start)
        previousEnd = end
        writes++
        if (end - lastReport < 5_000_000_000L) return
        Log.i("MusicUsbPipeline", "写入次数=$writes PCM缓冲毫秒=${bufferedFrames * 1000 / sampleRate}" +
            " USB在途毫秒=${pendingFrames * 1000 / sampleRate}" +
            " 最长供数间隔毫秒=${longestGap / 1_000_000} 最长写入毫秒=${longestWrite / 1_000_000}")
        lastReport = end
        longestGap = 0
        longestWrite = 0
        writes = 0
    }

    fun reset() { previousEnd = 0; longestGap = 0; longestWrite = 0; writes = 0; lastReport = System.nanoTime() }
    fun invalidate() { resetPending = true }
}
