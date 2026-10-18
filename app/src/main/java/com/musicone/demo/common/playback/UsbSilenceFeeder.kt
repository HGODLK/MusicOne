package com.musicone.demo

import java.util.concurrent.Executors

internal interface UsbSilenceTrack {
    fun play()
    fun write(samples: ShortArray, offset: Int, count: Int): Int
    fun pause()
    fun release()
}

/** 独立串行写线程持续送入静音，旧音轨释放后才能启动新一轮，不能只调用 play。 */
internal class UsbSilenceFeeder(
    private val openTrack: () -> UsbSilenceTrack,
    private val onFailure: (Exception) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicOne-UsbSilence").apply { isDaemon = true }
    }
    private var session: Session? = null
    private var released = false

    @Synchronized fun setActive(active: Boolean) {
        if (released || active == (session != null)) return
        session?.stop()
        session = if (active) Session().also { executor.execute(it) } else null
    }

    @Synchronized fun release() {
        if (released) return
        released = true
        session?.stop(); session = null
        executor.shutdownNow()
    }

    private inner class Session : Runnable {
        private val lock = Any()
        @Volatile private var stopped = false
        private var track: UsbSilenceTrack? = null
        private var thread: Thread? = null

        fun stop() = synchronized(lock) {
            stopped = true
            // pause 可中断另一线程中的阻塞 write；最终释放仍交给写线程，避免并发使用已释放音轨。
            runCatching { track?.pause() }
            thread?.interrupt()
        }

        override fun run() {
            synchronized(lock) { thread = Thread.currentThread() }
            val silence = ShortArray(SAMPLES_PER_CHUNK)
            var failureReported = false
            try {
                while (!stopped) {
                    var opened: UsbSilenceTrack? = null
                    try {
                        val created = openTrack()
                        opened = created
                        synchronized(lock) {
                            if (!stopped) { track = created; created.play() }
                        }
                        var offset = 0
                        while (!stopped) {
                            val written = created.write(silence, offset, silence.size - offset)
                            if (stopped) break
                            check(written in 0..(silence.size - offset)) { "静音占位轨写入失败：$written" }
                            if (written == 0) {
                                // 正常写入由 AudioTrack 阻塞节流；零写入时让出线程，不能忙等空转。
                                Thread.sleep(ZERO_WRITE_WAIT_MS)
                            } else {
                                failureReported = false
                                offset = (offset + written) % silence.size
                            }
                        }
                    } catch (_: InterruptedException) {
                        if (stopped) break
                    } catch (failure: Exception) {
                        if (!stopped && !failureReported) { onFailure(failure); failureReported = true }
                    } finally {
                        synchronized(lock) { track = null; runCatching { opened?.release() } }
                    }
                    if (!stopped) {
                        // 系统音轨失效后重建，退避期间仍可立即响应关闭和拔出。
                        try { Thread.sleep(RECREATE_WAIT_MS) } catch (_: InterruptedException) { }
                    }
                }
            } finally { synchronized(lock) { thread = null } }
        }
    }

    internal companion object {
        const val SAMPLE_RATE = 48_000
        const val SAMPLES_PER_CHUNK = SAMPLE_RATE / 10
        private const val ZERO_WRITE_WAIT_MS = 10L
        private const val RECREATE_WAIT_MS = 1_000L
    }
}
