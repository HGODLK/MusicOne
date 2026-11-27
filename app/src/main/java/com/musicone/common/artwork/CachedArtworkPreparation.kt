package com.musicone

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 只预热本地已缓存的封面，没命中的交给正常显示流程去下载，不在这里发网络请求。 */
internal suspend fun prepareCachedArtwork(urls: List<String?>, context: Context, maxSide: Int = 768) =
    prepareCachedArtwork(urls) { url -> ArtworkRepository.cached(url, context, maxSide) }

internal suspend fun prepareCachedArtwork(urls: List<String?>, readCached: suspend (String) -> Unit) = coroutineScope {
    val limit = Semaphore(3)
    // 倒序预热，让排在前面的封面最后进 LRU，避免长列表把首屏封面挤出内存缓存。
    urls.filterNotNull().filter(String::isNotBlank).distinct().asReversed().map { url ->
        async {
            limit.withPermit {
                try { readCached(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 单张缓存不可用时不阻塞其他封面及列表。 */ }
            }
        }
    }.awaitAll()
    Unit
}
