package com.musicone.source.qq

import com.musicone.source.sdk.SourceOperationException
import com.musicone.source.qq.internal.*

/** 宿主只依据公开错误类别决定重试，不能识别包私有异常类型。 */
internal fun Exception.toQqSdkFailure(): Exception = when {
    this is SourceOperationException -> this
    this is QqCredentialExpiredException || this is PlatformApiException && apiCode in setOf(301, 401) ->
        SourceOperationException("AUTH_REQUIRED", message ?: "QQ登录状态已失效，请重新登录", true)
    this is QqPlaybackItemUnavailableException -> SourceOperationException("TRACK_UNAVAILABLE", message ?: "当前曲目暂不可播放", false)
    this is PlatformSecurityVerificationRequired || this is QqSessionRequestException || this is PlatformApiException && apiCode in setOf(403, 429) ->
        SourceOperationException("PLAYBACK_RETRY_REQUIRED", message ?: "QQ请求暂被拦截，请完成验证后重试", true)
    this is PlatformApiException -> SourceOperationException("SOURCE_REQUEST_FAILED", message ?: "QQ请求失败，请重试", true)
    else -> this
}
