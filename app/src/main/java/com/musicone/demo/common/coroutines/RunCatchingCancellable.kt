package com.musicone.demo

import kotlinx.coroutines.CancellationException

/** 取消属于协程控制流，必须在失败提示、空结果和备用请求之前向上传递。 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
