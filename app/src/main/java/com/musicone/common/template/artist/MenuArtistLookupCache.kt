package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 菜单关闭不取消头像准备，同一宿主内按音源和歌手合并请求并保留已准备内容。 */
internal class MenuArtistLookupCache<T>(private val scope: CoroutineScope) {
    private val requests = LinkedHashMap<String, Deferred<Result<T>>>()
    private val ready = LinkedHashMap<String, T>()
    private val mutex = Mutex()

    fun peek(key: String): T? = ready[key]

    suspend fun load(key: String, loader: suspend () -> T): T {
        val request = mutex.withLock {
            requests.getOrPut(key) { scope.async { runCatching { loader() } } }
        }
        try {
            return request.await().getOrThrow().also { value ->
                mutex.withLock {
                    ready[key] = value
                    while (ready.size > 64) {
                        val oldest = ready.keys.first()
                        ready.remove(oldest)
                        requests.remove(oldest)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (request.isCompleted) mutex.withLock { if (requests[key] === request) requests.remove(key) }
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock { if (requests[key] === request) requests.remove(key) }
            throw error
        }
    }
}
