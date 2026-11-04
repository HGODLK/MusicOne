package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 沿用 0.2.0 的官方分页重试；不重复准备卡片，取消及未登录直接交还上层。 */
internal suspend fun <T> loadQqMusicFeedWithRetry(
    wait: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    repeat(3) { attempt ->
        try {
            return request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (attempt == 2 || error is PlatformApiException && error.apiCode == 301) throw error
            wait(600L * (attempt + 1))
        }
    }
    error("推荐分页重试未返回")
}
