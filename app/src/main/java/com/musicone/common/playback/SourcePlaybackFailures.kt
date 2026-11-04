package com.musicone

import com.musicone.source.sdk.SourceOperationException

internal fun Throwable.stopsSourcePlaybackFallback(): Boolean =
    this is SourceOperationException && code in setOf("AUTH_REQUIRED", "AUTH_EXPIRED", "SECURITY_VERIFICATION_REQUIRED", "REQUEST_REJECTED", "RATE_LIMITED", "PLAYBACK_RETRY_REQUIRED")

internal fun maySkipSourceFailure(error: Throwable): Boolean = when (error) {
    is SourceOperationException -> error.code == "TRACK_UNAVAILABLE"
    is java.io.IOException -> false
    else -> error is TrackUnavailableException
}
