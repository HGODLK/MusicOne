package com.musicone.demo

/** QQ 在线取票只跟随停止切换后的最后一首，完整缓存不等待。 */
internal const val QQ_ONLINE_PLAYBACK_SETTLE_DELAY_MS = 500L

/** 切歌时先保障音源与首帧，非关键请求等播放稳定后再依次启动。 */
internal const val PLAYBACK_LYRICS_DELAY_MS = 1_200L
internal const val PLAYBACK_LYRICS_TIMEOUT_MS = 3_000L
internal const val PLAYBACK_METADATA_DELAY_MS = 1_500L
internal const val PLAYBACK_CACHED_UPGRADE_DELAY_MS = 1_500L
internal const val PLAYBACK_QUALITY_DELAY_MS = 2_500L
