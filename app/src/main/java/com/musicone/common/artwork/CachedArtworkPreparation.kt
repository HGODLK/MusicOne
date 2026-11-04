package com.musicone

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 只读取已有图片缓存；未命中直接交还原显示链路，不在准备阶段下载。 */
internal suspend fun prepareCachedArtwork(urls: List<String?>, context: Context, maxSide: Int = 768) =
    prepareCachedArtwork(urls) { url -> ArtworkRepository.cached(url, context, maxSide) }

internal suspend fun prepareCachedArtwork(urls: List<String?>, readCached: suspend (String) -> Unit) = coroutineScope {
    val limit = Semaphore(3)
    // 优先显示的封面最后准备，避免较长列表的尾部图片挤出首屏内存缓存。
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
