package com.musicone

import kotlinx.coroutines.CancellationException

/** 捕获异常但直接重抛 CancellationException，防止把协程取消误当成普通失败吞掉。 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
