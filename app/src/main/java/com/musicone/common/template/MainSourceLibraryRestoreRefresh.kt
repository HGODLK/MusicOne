package com.musicone

import com.musicone.source.sdk.SourceFetchPolicy

internal data class MainSourceLibraryRefreshRequest(
    val policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST,
    val forceArtworkIds: Set<String> = emptySet(),
)

/** 缓存恢复期间只合并刷新意图；发布缓存后再核对，旧会话不能消费新会话的请求。 */
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
