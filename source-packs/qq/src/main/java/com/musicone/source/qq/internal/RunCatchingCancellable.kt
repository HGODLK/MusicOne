package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException

/** 类似 runCatching，但遇到 CancellationException 时直接重新抛出，避免吞掉协程取消。 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
