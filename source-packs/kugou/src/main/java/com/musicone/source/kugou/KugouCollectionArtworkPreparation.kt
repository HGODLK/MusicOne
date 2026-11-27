package com.musicone.source.kugou

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 先把歌单目录返回给页面展示，缺封面的在后台逐个补齐；同一版本去重，切号或修改歌单时取消。 */
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
            catch (_: Exception) { /* 封面拉取失败就先用原封面，等下次进入再试，不在这里循环重试 */ }
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
