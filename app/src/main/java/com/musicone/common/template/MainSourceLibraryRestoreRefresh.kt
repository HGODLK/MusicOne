package com.musicone

import com.musicone.source.sdk.SourceFetchPolicy

internal data class MainSourceLibraryRefreshRequest(
    val policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST,
    val forceArtworkIds: Set<String> = emptySet(),
)

/** 读本地缓存期间先把期间来的刷新请求攒起来，等缓存显示完再统一发网查，同时隔离旧会话。 */
internal class MainSourceLibraryRestoreRefresh {
    private var restoration: Any? = null
    private var pending: MainSourceLibraryRefreshRequest? = null

    fun begin(): Any = Any().also {
        restoration = it
        pending = null
    }

    fun cancel() {
        restoration = null
        pending = null
    }

    fun defer(policy: SourceFetchPolicy, forceArtworkIds: Set<String>): Boolean {
        if (restoration == null) return false
        val previous = pending
        pending = MainSourceLibraryRefreshRequest(
            if (previous?.policy == SourceFetchPolicy.REFRESH) SourceFetchPolicy.REFRESH else policy,
            previous?.forceArtworkIds.orEmpty() + forceArtworkIds,
        )
        return true
    }

    fun complete(token: Any): MainSourceLibraryRefreshRequest? {
        if (restoration !== token) return null
        val request = pending ?: MainSourceLibraryRefreshRequest()
        cancel()
        return request
    }
}
