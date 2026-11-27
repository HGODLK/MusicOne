package com.musicone

/** 连续切歌时防抖 500ms 再请求在线地址，已整首缓存的歌不用等。 */
internal const val ONLINE_PLAYBACK_SETTLE_DELAY_MS = 500L

/** 切歌时优先让音乐出声，歌词等次要请求延迟 400ms 再发。 */
internal const val PLAYBACK_LYRICS_DELAY_MS = 400L
internal const val PLAYBACK_LYRICS_TIMEOUT_MS = 3_000L
internal const val PLAYBACK_LYRICS_RETRY_TIMEOUT_MS = 10_000L
internal const val PLAYBACK_METADATA_DELAY_MS = 1_500L
internal const val PLAYBACK_CACHED_UPGRADE_DELAY_MS = 1_500L
internal const val PLAYBACK_QUALITY_DELAY_MS = 2_500L
