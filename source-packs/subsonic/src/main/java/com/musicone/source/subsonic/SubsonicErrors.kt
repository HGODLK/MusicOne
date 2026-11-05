package com.musicone.source.subsonic

import com.musicone.source.sdk.SourceOperationException
import com.musicone.source.subsonic.internal.SubsonicApiException
import com.musicone.source.subsonic.internal.SubsonicTransportException
import org.json.JSONException
import kotlin.coroutines.cancellation.CancellationException

/** 宿主只认识公开错误类别；消息可直接展示，不含带凭据的地址或响应正文。 */
internal fun Exception.toSubsonicSdkFailure(): Exception = when (this) {
    is CancellationException, is SourceOperationException -> this
    is SubsonicApiException -> when (code) {
        40 -> SourceOperationException("AUTH_EXPIRED", "服务器账号或密码已变更，请重新登录")
        50 -> SourceOperationException("REQUEST_REJECTED", message ?: "当前账号没有执行此操作的权限")
        70 -> SourceOperationException("SOURCE_REQUEST_FAILED", message ?: "服务器上找不到该内容")
        else -> SourceOperationException("SOURCE_REQUEST_FAILED", message ?: "服务器请求失败")
    }
    is SubsonicTransportException -> SourceOperationException("SOURCE_REQUEST_FAILED", message ?: "与服务器通信失败", true)
    is JSONException -> SourceOperationException("SOURCE_REQUEST_FAILED", "服务器返回了无法识别的数据")
    is IllegalArgumentException, is IllegalStateException, is UnsupportedOperationException -> this
    else -> SourceOperationException("SOURCE_REQUEST_FAILED", "服务器请求失败")
}

internal val Exception.rejectsSubsonicCredential: Boolean get() = this is SubsonicApiException && code == 40

internal fun subsonicLoginRequired() = SourceOperationException("AUTH_REQUIRED", "请先登录 Subsonic 服务器")
