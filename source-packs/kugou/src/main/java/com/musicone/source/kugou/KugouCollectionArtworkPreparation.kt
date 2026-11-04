package com.musicone.source.kugou

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 目录先交付，缺失封面在后台依次准备；同一歌单版本合并，换号或写入时取消。 */
internal class KugouCollectionArtworkPreparation {
    private data class Pending(val version: Any, val job: Job)
    private val pending = mutableMapOf<String, Pending>()
    private val serial = Mutex()

    @Synchronized fun prepare(scope: CoroutineScope, key: String, version: Any, work: suspend () -> Unit): Job {
        pending[key]?.takeIf { it.version == version }?.let { return it.job }
        pending.remove(key)?.job?.cancel()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var completed = false
            try { serial.withLock { ensureActive(); work() }; completed = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 保留目录已有封面，下次读取允许重试，不主动触发刷新循环。 */ }
            finally {
                val currentJob = currentCoroutineContext()[Job]
                if (!completed) synchronized(this@KugouCollectionArtworkPreparation) {
                    if (pending[key]?.job == currentJob) pending.remove(key)
                }
            }
        }
        pending[key] = Pending(version, job)
        job.start()
        return job
    }

    @Synchronized fun clear() {
        pending.values.forEach { it.job.cancel() }
        pending.clear()
    }
}
