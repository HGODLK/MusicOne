package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 推荐流分页请求的自动重试包装（协程取消和未登录直接往外抛）。 */
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
