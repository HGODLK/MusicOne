package com.musicone.demo

import java.util.ArrayDeque

/** 原生输出仅由工作线程调用；interrupt 只发送可跨线程的中断信号。 */
internal interface UsbOutputTransport {
    val completedFrames: Long
    fun interrupt()
    fun flush()
    fun pollPending(): Boolean
    fun finish()
}

/** 队列、正在写入的数据和原生尾部共享同一生命周期，清理完成前不接收新数据。 */
internal class UsbOutputWorker<T>(
    private val transport: UsbOutputTransport,
    private val write: (T) -> Unit,
    private val recycle: (T) -> Unit,
    private val initializeThread: () -> Unit = {},
) {
    private val lock = Object()
    private val queue = ArrayDeque<T>()
    private var closed = false
    private var paused = false
    private var generation = 0L
    private var flushedGeneration = 0L
    private var busy = false
    private var nativePending = false
    private var endRequested = false
    private var ended = false
    private var failure: Exception? = null
    @Volatile var completedFrames = 0L
        private set
    private val thread = Thread(::run, "MusicOneUsbAudio")

    fun start() = thread.start()

    fun enqueue(value: T): Boolean = synchronized(lock) {
        if (closed || failure != null || generation != flushedGeneration || endRequested || queue.size >= 128) false
        else {
            queue.addLast(value)
            lock.notifyAll()
            true
        }
    }

    fun queueSize(): Int = synchronized(lock) { queue.size }
    fun hasPendingData(): Boolean = synchronized(lock) {
        queue.isNotEmpty() || busy || nativePending || generation != flushedGeneration
    }
    fun isEnded(): Boolean = synchronized(lock) { endRequested && ended && failure == null }
    fun error(): Exception? = synchronized(lock) { failure }
    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false; lock.notifyAll() }
    fun finish() = synchronized(lock) { endRequested = true; lock.notifyAll() }

    fun flush() = synchronized(lock) {
        check(!closed) { "USB 写线程已停止" }
        val requested = ++generation
        while (queue.isNotEmpty()) recycle(queue.removeFirst())
        endRequested = false
        ended = false
        failure = null
        transport.interrupt()
        lock.notifyAll()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (flushedGeneration < requested && failure == null && !closed) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "USB 清理超时，未恢复写入" }
            lock.wait((remaining / 1_000_000L).coerceAtLeast(1))
        }
        failure?.let { throw it }
        check(!closed) { "USB 清理期间写线程已停止" }
    }

    fun stop() {
        synchronized(lock) {
            closed = true
            ++generation
            transport.interrupt()
            while (queue.isNotEmpty()) recycle(queue.removeFirst())
            lock.notifyAll()
        }
        thread.join(3_000)
        check(!thread.isAlive) { "USB 写线程未退出，不能释放原生资源" }
    }

    private fun run() {
        initializeThread()
        while (true) {
            val current: Long
            val flushing: Boolean
            val finishing: Boolean
            val buffer: T?
            synchronized(lock) {
                while (!closed && (failure != null || (generation == flushedGeneration &&
                    (paused || queue.isEmpty()) && !nativePending &&
                    (!endRequested || ended || paused)))) lock.wait()
                if (closed) return
                current = generation
                flushing = current != flushedGeneration
                buffer = if (!flushing && !paused && queue.isNotEmpty()) queue.removeFirst() else null
                finishing = !flushing && !paused && buffer == null && queue.isEmpty() && endRequested && !ended
                busy = true
            }
            try {
                when {
                    flushing -> transport.flush()
                    buffer != null -> write(buffer)
                    finishing -> transport.finish()
                }
                val pending = transport.pollPending()
                val frames = transport.completedFrames
                synchronized(lock) {
                    if (current == generation) {
                        nativePending = pending
                        completedFrames = frames
                        if (flushing) flushedGeneration = current
                        if (finishing) ended = !pending
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) { if (current == generation) failure = error }
            } finally {
                buffer?.let(recycle)
                synchronized(lock) { busy = false; lock.notifyAll() }
            }
            synchronized(lock) {
                if (!closed && current == generation && nativePending && (paused || queue.isEmpty())) lock.wait(2)
            }
        }
    }
}
