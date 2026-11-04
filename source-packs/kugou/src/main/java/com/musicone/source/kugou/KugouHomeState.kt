package com.musicone.source.kugou

import com.musicone.source.sdk.SourceHomeSection
import com.musicone.source.sdk.SourceRecentSnapshot
import com.musicone.source.sdk.SourceTrackDetails

internal data class KugouHomeOwner(val accountId: String?, val revision: Long)

internal data class KugouHomeState(
    val owner: KugouHomeOwner? = null,
    val homeSections: List<SourceHomeSection> = emptyList(),
    val daily: List<SourceTrackDetails> = emptyList(),
    val recent: SourceRecentSnapshot = SourceRecentSnapshot(),
    val homeLoaded: Boolean = false,
    val homeDate: String? = null,
    val dailyLoaded: Boolean = false,
    val dailyDate: String? = null,
    val recentLoaded: Boolean = false,
    val loadingHome: Boolean = false,
    val loadingDaily: Boolean = false,
    val loadingRecent: Boolean = false,
    val homeError: String? = null,
    val dailyError: String? = null,
    val recentError: String? = null,
)
